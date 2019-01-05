package io.appalachian.vapor.vapord

import akka.{Done, NotUsed}
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.io.{IO, Udp}
import akka.pattern.{ask, pipe}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}
import java.net.InetSocketAddress
import java.time._
import java.time.temporal.ChronoUnit

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import spray.json._

object UnknownMessage {
  def apply(message: Any): UnknownMessage = new UnknownMessage(message)
}

class UnknownMessage(message: Any) extends RuntimeException(s"Unknown: $message")

object Oscillator {
  private val granularity = 250.milliseconds
  private val pauseLimitSeconds = 3

  case class Ref(actorRef: ActorRef)
  case object Tick
  case class Time(value: Instant)

  def spawn(name: String)(implicit context: ActorContext): Oscillator.Ref =
    Ref(context.actorOf(Props[Oscillator], name))
}

class Oscillator private () extends Actor with Timers {
  import Oscillator._

  private var last: Long = Instant.now().toEpochMilli

  override def preStart(): Unit = {
    timers.startPeriodicTimer("tick", Tick, granularity)
  }

  private val granularityMs = granularity.toMillis

  override def receive: Receive = {
    case Tick =>
      // @FIXME some marshalling back and forth here, probably doesn't matter much..
      val current = Instant.now().toEpochMilli * granularityMs / granularityMs

      // With NTP and other clock adjustments, time can go
      // backwards so we guard against that.
      //
      // Also, if we were suspended, then we'll bound the period
      // to reduce CPU consumption
      if (current > last) {
        var value =
          if (current - last < pauseLimitSeconds)
            last + 1
          else
            current

        while (value <= current) {
          context.parent ! Time(Instant.ofEpochMilli(value))
          last = value
          value += granularityMs
        }
      }
  }
}

object MetricsCollector {
  private val gaugeListLimit = 30

  /**
    * If an event is specified with a period of 0,
    * use this period instead (seconds)
    */
  private val defaultPeriod = 60.seconds

  /**
    * The number of validate periods, i.e. events can be
    * rolled up over any interval (by second) upto this
    * many seconds.
    */
  private val numberOfPeriods = 600.seconds

  /**
    * The maximum number of gauges to hold
    */
  private val maxGauges = 16384

  /**
    * The maximum number of entries for a given
    * gauge
    */
  private val maxGaugeEntries = 16384

  /**
    * The maximum gauge lifetime, currently ~2 weeks
    * but will deviate depending upon DST, timezone, etc.
    */
  private val maxGaugeLife = 14.days

  /**
    * Remove old metrics this often
    */
  private val removeOldMetricsInterval = 300.seconds

  def window(currentTime: Instant, data: Iterator[GaugeEntry], windowLength: WindowLength): Vector[GaugeEntry] = {
    case class WindowData(name: String, time: Long, sum: Long, size: Long)

    val oldest = currentTime.toEpochMilli - windowLength.windowLength.toMillis
    val divide = windowLength.averagePeriod.toMillis

    data
      .dropWhile(_.when < oldest)
      .foldLeft(Vector.empty[WindowData]) { case (a, GaugeEntry(n, t, v)) =>
        val time = t / divide * divide

        a.lastOption match {
          case Some(WindowData(_, `time`, sum, size)) =>
            a.dropRight(1) :+ WindowData(n, time, sum + v, size + 1)

          case _ =>
            a :+ WindowData(n, time, v, 1)
        }
      }
      .map { case WindowData(n, time, sum, size) =>
        GaugeEntry(n, time, if (size == 0) 0 else sum / size)
      }
  }

  def props(host: String, port: Int): Props =
    Props(new MetricsCollector(host, port))

  sealed trait Metric
  case class Gauge(name: String, value: Long, when: Option[Instant]) extends Metric
  case class Event(name: String, value: Long, rollUpPeriod: FiniteDuration) extends Metric
  case object Stop

  object GaugeEntries {
    case class Reply(source: Source[GaugeEntry, NotUsed])
  }

  object GaugeData {
    case class Reply(data: Vector[GaugeEntry])
  }

  object GaugeNames {
    case class Reply(source: Source[GaugeNamesChanged, NotUsed])
  }

  case class GaugeData(name: String, windowLength: WindowLength)

  def parseMetric(data: ByteString): Option[Metric] = {
    val isAscii09 = (c: Char) => c >= '0' && c <= '9'
    val isNumber = (value: String) => value.nonEmpty && value.forall(isAscii09)

    val parsed = data.utf8String.trim
    val components = parsed.split('/')

    if (components.length == 3 && components(0) == "g" && isNumber(components(2))) {
      Some(Gauge(components(1), components(2).toLong, None))
    } else if (components.length == 4 && components(0) == "e" && components.length == 4 && isNumber(components(2)) && isNumber(components(3))) {
      val providedPeriod = components(3).toLong.millis

      if (providedPeriod.toMillis <= numberOfPeriods.toMillis)
        Some(Event(components(1), components(2).toLong, components(3).toLong.millis))
      else
        None
    } else {
      None
    }
  }
}

/**
 * Listens for metrics data on the provided UDP port. Parses this data
 * and keeps a rolling window of metrics in memory.
 *
 * Metrics can be *events* or *gauges* and for simplicitly are decoded
 * as UTF-8 strings. Events are summed up into gauges over a specified
 * number of seconds.
 *
 * Example metrics:
 *
 * "g.my-metric/12345" yields Gauge("my-metric", 12345)
 * "e.my-event/5/60" yields Event("my-event", 5, 60)
 */
class MetricsCollector private (host: String, port: Int) extends Actor with ActorLogging with Stash {
  import MetricsCollector._

  private implicit val executionContext: ExecutionContext = context.dispatcher
  private implicit val system: ActorSystem = context.system
  private implicit val materializer: Materializer = ActorMaterializer()
  private implicit val timeout: Timeout = Timeout(10.seconds)

  private val oscillator = Oscillator.spawn("ocillator")

  private var currentTime = Option.empty[Instant]
  private var maybeSocket = Option.empty[ActorRef]

  private val metricDatabase = new MetricDatabase(maxGauges, maxGaugeEntries, maxGaugeLife)

  override def preStart(): Unit = {
    IO(Udp) ! Udp.Bind(self, new InetSocketAddress(host, port))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    maybeSocket.foreach { s =>
      s ! Udp.Unbind
    }
  }

  override def receive: Receive = {
    case Udp.Bound(local) =>
      log.info("metrics/udp: {}", local)

      val socket = sender()
      maybeSocket = Some(socket)
      context.become(running(socket))

    case MetricsCollector.Stop =>
      stash()
  }

  private def running(socket: ActorRef): Receive = {
    case Udp.Received(data, remote) =>
      currentTime.foreach { time =>
        parseMetric(data) match {
          case Some(Event(name, value, period)) =>
            metricDatabase.ingestEvent(time, name, value, period)

          case Some(Gauge(name, value, when)) =>
            metricDatabase.ingestGauge(time, name, value)

          case None =>
        }
      }

    case GaugeEntries =>
      sender() ! GaugeEntries.Reply(metricDatabase.dataStream)

    case GaugeNames =>
      sender() ! GaugeNames.Reply(metricDatabase.nameStream)

    case GaugeData(name, windowLength) =>
      val data = metricDatabase.gaugeData(name)

      currentTime match {
        case Some(time) =>
          sender() ! GaugeData.Reply(window(time, data, windowLength))

        case None =>
          sender() ! GaugeData.Reply(Vector.empty)
      }

    case Stop =>
      context.stop(oscillator.actorRef)

      (socket ? Udp.Unbind)
        .mapTo[Udp.Unbound]
        .pipeTo(self)
        .map(_ => Done)
        .pipeTo(sender)

      maybeSocket = None

      context.become(stopping)

    case Oscillator.Time(value) =>
      val valueMs = value.toEpochMilli

      currentTime = Some(value)

      for (i <- 1L to numberOfPeriods.toMillis) {
        if (valueMs % i == 0) {
          metricDatabase.rollUp(value, i.millis)
        }
      }

      if (valueMs % removeOldMetricsInterval.toMillis == 0) {
        val removed = metricDatabase.removeOldMetrics(value)

        log.info("Holding [{}] gauge entries after removing [{}]", metricDatabase.numberOfGaugeEntries, removed.gaugeEntriesRemoved)
      }

    case other => throw UnknownMessage(other)
  }

  private def stopping: Receive = {
    case Udp.Unbound =>
      log.info("metrics/udp unbound")
      context.stop(self)
  }

}

sealed trait GaugeNamesChanged

case class GaugeNameAdded(name: String) extends GaugeNamesChanged

case class GaugeNameRemoved(name: String) extends GaugeNamesChanged

case class GaugeEntry(name: String, when: Long, value: Long)

sealed trait WindowLength {
  def averagePeriod: FiniteDuration
  def windowLength: FiniteDuration
}

object WindowLength {
  case object P1H extends WindowLength {
    // 360
    val averagePeriod: FiniteDuration = 10.seconds
    val windowLength: FiniteDuration = 1.hour
  }

  case object P1D extends WindowLength {
    // 360
    val averagePeriod: FiniteDuration = 4.minutes
    val windowLength: FiniteDuration = 1.day
  }

  case object P2W extends WindowLength {
    // 336
    val averagePeriod: FiniteDuration = 1.hour
    val windowLength: FiniteDuration = 14.days
  }
}

object MetricDatabase {


  case class Removed(gaugesRemoved: Int, gaugeEntriesRemoved: Int)

  val GaugeBufferSize: Int = 2048
  val GaugeNameInterval: FiniteDuration = 1.second
  val MaxGaugeNames: Int = 65536
  val TimeLimitMs: Int = 300000
}

/**
 * Stores gauges and events, and provides methods to
 * roll up events into gauges as well as remove old data.
 *
 * This class does not have to be used with a particular
 * unit of time, as long as the same unit of time is used
 * for all method invocations.
 *
 * This class is not thread safe so proper care must be
 * taken if using in a concurrent environment.
 */
class MetricDatabase(maxGauges: Long, maxGaugeEntries: Long, maxGaugeLife: FiniteDuration)(implicit mat: Materializer) {
  import MetricDatabase._



  private val events = mutable.HashMap.empty[Long, mutable.HashMap[String, Long]]
  private val gauges = mutable.HashMap.empty[String, mutable.TreeMap[Long, Long]]
  private val gaugesLastUpdated = mutable.HashMap.empty[String, Long]

  private val (gaugeEntrySink, gaugeEntrySource) =
    MergeHub.source[GaugeEntry]
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

  @volatile
  private var latest = Vector.empty[GaugeEntry]

  @volatile
  private var allNames = Set.empty[String]

  gaugeEntrySource.runWith(Sink.foreach { gaugeEntry =>
    val now = System.currentTimeMillis()
    latest = (latest :+ gaugeEntry).dropWhile(_.when < (now - TimeLimitMs) / 1000)
  })

  def gaugeData(name: String): Iterator[GaugeEntry] =
    gauges
      .get(name)
      .map(_.toIterator)
      .getOrElse(Iterator.empty)
      .map { case (when, value) => GaugeEntry(name, when, value) }

  /**
   * Emits `GaugeEntry`s as they occur. Entries are grouped
   * within a 1 second window and averaged out (mean).
   *
   * This differs slightly from groupedWithin given that
   * we wish to emit events from `latest` immediately when
   * the when value changes.
    */
  def dataStream: Source[GaugeEntry, NotUsed] = {
    sealed trait Element
    case class Wrapper(value: GaugeEntry) extends Element
    case object Tick extends Element

    case class Accum(name: String, when: Long, sum: Long, count: Long)

    Source
      .fromIterator(() => latest.iterator)
      .concat(gaugeEntrySource)
      .groupBy(MaxGaugeNames, _.name)
      .map[Element](Wrapper.apply)
      .merge(Source.tick[Element](0.seconds, 1.second, Tick))
      .scan[(Option[Accum], Option[GaugeEntry])](None -> None) {
        case ((Some(Accum(name, when, sum, count)), _), Tick) =>
          None -> Some(GaugeEntry(name, when, sum / count))

        case ((None, _), Tick) =>
          None -> None

        case ((Some(Accum(name, when, sum, count)), _), Wrapper(next)) if next.when == when =>
          Some(Accum(name, when, sum + next.value, count + 1)) -> None

        case ((Some(Accum(name, when, sum, count)), _), Wrapper(next)) =>
          Some(Accum(next.name, next.when, next.value, 1L)) -> Some(GaugeEntry(name, when, sum / count))

        case ((None, _), Wrapper(next)) =>
          Some(Accum(next.name, next.when, next.value, 1L)) -> None
      }
      .mergeSubstreams
      .collect {
        case (_, Some(gaugeEntry)) =>
          gaugeEntry
      }
      .buffer(GaugeBufferSize, OverflowStrategy.dropHead)
  }

  def nameStream: Source[GaugeNamesChanged, NotUsed] =
    Source
      .tick(0.seconds, GaugeNameInterval, NotUsed)
      .map(_ => allNames)
      .scan((Set.empty[String], Set.empty[String], Set.empty[String])) { case ((last, _, _), next) =>
        (next, next.diff(last), last.diff(next))
      }
      .mapConcat { case (_, added, removed) =>
        added.map(GaugeNameAdded.apply) ++ removed.map(GaugeNameRemoved.apply)
      }
      .mapMaterializedValue(_ => NotUsed)

  def ingestEvent(currentTime: Instant, name: String, value: Long, rollUpPeriod: FiniteDuration): Unit = {
    val entry = events.getOrElseUpdate(rollUpPeriod.toMillis, mutable.HashMap.empty)
    entry.update(name, entry.getOrElse(name, 0L) + value)
  }

  /**
   * Adds a gauge value to the database. If a value is already stored for
   * the given gauge, the mean average of the currently stored value and
   * the provided value is stored.
   */
  def ingestGauge(currentTime: Instant, name: String, value: Long): Unit = {
    val currentTimeMillis = currentTime.toEpochMilli
    val collection = gauges.getOrElseUpdate(name, mutable.TreeMap.empty)

    val gaugeEntry = collection.get(currentTimeMillis) match {
      case Some(existing) =>
        val newValue = (existing + value) / 2
        collection.update(currentTimeMillis, (existing + value) / 2)
        GaugeEntry(name, currentTimeMillis, newValue)
      case None =>
        collection.update(currentTimeMillis, value)
        GaugeEntry(name, currentTimeMillis, value)
    }

    Source.single(gaugeEntry).runWith(gaugeEntrySink)

    gaugesLastUpdated.update(name, currentTime.toEpochMilli)

    allNames = allNames + name
  }

  def numberOfGaugeEntries: Long =
    gauges.foldLeft(0L)(_ + _._2.size)

  def removeOldMetrics(currentTime: Instant): Removed = {
    var gaugeEntriesRemoved = 0
    var gaugesRemoved = 0

    def removeGauge(name: String): Unit = {
      gauges.remove(name)
      allNames = allNames - name
      gaugesLastUpdated.remove(name)
      gaugesRemoved += 1
    }

    @annotation.tailrec
    def removeGauges(oldest: List[(String, Long)]): Unit =
      if (gauges.size > maxGauges) {
        // if .head doesn't exist, that's a bug
        val next = oldest.head

        removeGauge(next._1)

        removeGauges(oldest.tail)
      }

    val oldestAllowed = currentTime.minus(maxGaugeLife.toMillis, ChronoUnit.MILLIS).toEpochMilli

    gauges.foreach { case (name, entries) =>
      val initialSize = entries.size

      while (entries.nonEmpty && entries.head._1 < oldestAllowed) {
        entries.remove(entries.head._1)
      }

      while (entries.nonEmpty && entries.size > maxGaugeEntries) {
        entries.remove(entries.head._1)
      }

      gaugeEntriesRemoved += initialSize - entries.size

      if (entries.isEmpty) {
        removeGauge(name)
      }
    }

    if (gauges.size > maxGauges) {
      // this is a bit performance intensive -- have to convert to list
      // and sort ~maxGauges entries (could be more if bursty load)
      removeGauges(gaugesLastUpdated.toList.sortBy(_._2))
    }

    Removed(gaugesRemoved, gaugeEntriesRemoved)
  }

  def rollUp(currentTime: Instant, period: FiniteDuration): Unit = {
    events.get(period.toMillis).foreach { entries =>
      entries.foreach { case (name, value) =>
        ingestGauge(currentTime, name, value)
      }

      events.remove(period.toMillis)
    }
  }
}

case class Chart(name: String)
case class ChartData(name: String, data: Seq[ChartEntry])
case class ChartEntry(when: Long, value: Long)
case class ChartListing(charts: Seq[Chart])

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val chartDataFormat: RootJsonFormat[Chart] =
    jsonFormat1(Chart)

  implicit val chartEntryFormat: RootJsonFormat[ChartEntry] =
    jsonFormat2(ChartEntry)

  implicit val chartFormat: RootJsonFormat[ChartData] =
    jsonFormat2(ChartData)

  implicit val chartListingFormat: RootJsonFormat[ChartListing] =
    jsonFormat1(ChartListing)

  implicit val gaugeEntryFormat: RootJsonFormat[GaugeEntry] =
    jsonFormat3(GaugeEntry)

  implicit val gaugeNameAddedFormat: RootJsonFormat[GaugeNameAdded] =
    jsonFormat1(GaugeNameAdded)

  implicit val gaugeNameRemovedFormat: RootJsonFormat[GaugeNameRemoved] =
    jsonFormat1(GaugeNameRemoved)
}

object UserInterface {
  def props(metricsCollector: ActorRef, host: String, port: Int): Props =
    Props(new UserInterface(metricsCollector, host, port))

  case object Stop

  private case object Unbound
}

class UserInterface private (metricsCollector: ActorRef, host: String, port: Int) extends Actor with ActorLogging with Stash {
  import UserInterface._

  private implicit val executionContext: ExecutionContext = context.dispatcher
  private implicit val system: ActorSystem = context.system
  private implicit val materializer: Materializer = ActorMaterializer()
  private implicit val timeout: Timeout = Timeout(10.seconds)

  private object UIRoute extends JsonSupport {
    import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

    val route = concat(
      pathPrefix("api")(Route.seal(
        concat(
          (get & pathPrefix("gauge-entries" / Segment)) { name =>

            def windowedData(windowLength: WindowLength) = {
              val data = (metricsCollector ? MetricsCollector.GaugeData(name, windowLength))
                .mapTo[MetricsCollector.GaugeData.Reply]

              complete(
                Source
                  .fromFutureSource(data.map(entries => Source(entries.data)))
                  .map(gaugeEntry => ServerSentEvent(gaugeEntry.toJson.compactPrint, "GaugeEntry"))
              )

            }

            concat(
              path("1h") {
                windowedData(WindowLength.P1H)
              },
              path("1d") {
                windowedData(WindowLength.P1D)
              },
              path("2w") {
                windowedData(WindowLength.P2W)
              },
              pathEnd {
                val data = (metricsCollector ? MetricsCollector.GaugeEntries)
                  .mapTo[MetricsCollector.GaugeEntries.Reply]

                complete(
                  data.map(
                    _
                      .source
                      .filter(_.name.startsWith(name))
                      .map(gaugeEntry => ServerSentEvent(gaugeEntry.toJson.compactPrint, "GaugeEntry"))
                      .keepAlive(10.seconds, () => ServerSentEvent.heartbeat)
                  )
                )
              }
            )

          },
          (get & path("gauge-names")) {
            val data = (metricsCollector ? MetricsCollector.GaugeNames)
              .mapTo[MetricsCollector.GaugeNames.Reply]

            complete(
              data.map(
                _
                  .source
                  .map {
                    case nameAdded: GaugeNameAdded =>
                      ServerSentEvent(nameAdded.toJson.compactPrint, "GaugeNameAdded")
                    case nameRemoved: GaugeNameRemoved =>
                      ServerSentEvent(nameRemoved.toJson.compactPrint, "GaugeNameRemoved")
                  }
                  .keepAlive(1.second, () => ServerSentEvent.heartbeat)
              )
            )
          }
        )
      )),
      pathEndOrSingleSlash(getFromResource("assets/index.html")),
      getFromResourceDirectory("assets")
    )
  }

  private var binding = Option.empty[Http.ServerBinding]

  override def preStart(): Unit = {
    Http()
      .bindAndHandle(UIRoute.route, host, port)
      .pipeTo(self)
  }

  override def receive: Receive = {
    case b: Http.ServerBinding =>
      log.info("ui/http: {}", b.localAddress)
      binding = Some(b)
      unstashAll()
      context.become(running)

    case Stop =>
      stash()
  }

  private def running: Receive = {
    case Stop =>
      binding.foreach { bn =>
        bn
          .unbind()
          .map(_ => Unbound)
          .pipeTo(self)
          .map(_ => Done)
          .pipeTo(sender)
      }

      binding = None

      context.become(stopping)
  }

  private def stopping: Receive = {
    case Unbound =>
      log.info("ui/http unbound")
      context.stop(self)
  }
}

object Server {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("vapord")


    val settings = Settings(system)
    val metricsCollector = system.actorOf(MetricsCollector.props(settings.metricsBindHost, settings.metricsBindPort), "metricsCollector")
    val userInterface = system.actorOf(UserInterface.props(metricsCollector, settings.uiBindHost, settings.uiBindPort), "userInterface")

    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "unbind") { () =>
      implicit val executionContext: ExecutionContext = system.dispatcher
      implicit val timeout: Timeout = Timeout(10.seconds)

      val stopUserInterface = (userInterface ? UserInterface.Stop).mapTo[Done]
      val stopMetricsCollector = (metricsCollector ? MetricsCollector.Stop).mapTo[Done]

      Future
        .sequence(Seq(stopUserInterface, stopMetricsCollector))
        .map(_ => Done)
    }
  }
}
