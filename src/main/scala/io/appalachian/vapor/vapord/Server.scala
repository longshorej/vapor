package io.appalachian.vapor.vapord

import akka.{Done, NotUsed}
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.io.{IO, Udp}
import akka.pattern.{ask, pipe}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.time._

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.io.StdIn
import spray.json._

object UnknownMessage {
  def apply(message: Any): UnknownMessage = new UnknownMessage(message)
}

class UnknownMessage(message: Any) extends RuntimeException(s"Unknown: $message")

object Oscillator {
  case class Ref(actorRef: ActorRef)
  case object Tick
  case class Time(value: Long)

  def spawn(name: String)(implicit context: ActorContext): Oscillator.Ref =
    Ref(context.actorOf(Props[Oscillator], name))
}

class Oscillator private () extends Actor with Timers {
  import Oscillator._

  private var last: Long = Instant.now().getEpochSecond

  override def preStart(): Unit = {
    timers.startPeriodicTimer("tick", Tick, 100.milliseconds)
  }

  override def receive: Receive = {
    case Tick =>
      val current = Instant.now().getEpochSecond

      // With NTP and other clock adjustments, time can go
      // backwards so we guard against that.
      if (current > last) {
        var value = last + 1
        while (value <= current) {
          context.parent ! Time(value)
          last = value
          value += 1
        }
      }
  }
}

object MetricsCollector {
  def window5m(currentTime: Long, data: Iterator[GaugeEntry]): Vector[GaugeEntry] = {
    case class WindowData(time: Long, sum: Long, size: Long)

    val oldest = currentTime - (5 * 60)

    data
      .dropWhile(_.when < oldest)
      .foldLeft(Vector.empty[WindowData]) { case (a, GaugeEntry(t, v)) =>
        val time = t / 5 * 5

        a.lastOption match {
          case Some(WindowData(`time`, sum, size)) =>
            a.dropRight(1) :+ WindowData(time, sum + v, size + 1)

          case _ =>
            a :+ WindowData(time, v, 1)
        }
      }
      .map { case WindowData(time, sum, size) =>
        GaugeEntry(time, if (size == 0) 0 else sum / size)
      }
  }

  def props(host: String, port: Int): Props =
    Props(new MetricsCollector(host, port))

  sealed trait Metric
  case class Gauge(name: String, value: Long, when: Option[Long]) extends Metric
  case class Event(name: String, value: Long, rollUpPeriod: Int) extends Metric
  case object Stop

  object GaugeEntries {
    case class Reply(source: Source[GaugeEntry, NotUsed])
  }

  object GaugeData {
    case class Reply(data: Vector[GaugeEntry])
  }

  case class GaugeData(name: String)

  object ListGauges {
    case class Reply(names: Seq[String])
  }

  case class ListGauges(startingWith: String)

  def parseMetric(data: ByteString): Option[Metric] = {
    val isAscii09 = (c: Char) => c >= '0' && c <= '9'
    val isNumber = (value: String) => value.nonEmpty && value.forall(isAscii09)

    val parsed = data.utf8String.trim
    val components = parsed.split('/')

    if (components.length == 3 && components(0) == "g" && isNumber(components(2))) {
      Some(Gauge(components(1), components(2).toLong, None))
    } else if (components.length == 4 && components(0) == "e" && components.length == 4 && isNumber(components(2)) && isNumber(components(3))) {
      Some(Event(components(1), components(2).toLong, components(3).toInt))
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

  private val gaugeListLimit = 30

  /**
   * If an event is specified with a period of 0,
   * use this period instead (seconds)
   */
  private val defaultPeriod = 60L

  /**
   * The number of validate periods, i.e. events can be
   * rolled up over any interval (by second) upto this
   * many seconds.
   */
  private val numberOfPeriods = 600

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
   * The maximum gauge lifetime in seconds, currently ~2 weeks
   * but will deviate depending upon DST, timezone, etc.
   */
  private val maxGaugeLife = 86400L * 14L

  /**
   * Remove old metrics this often (seconds)
   */
  private val removeOldMetricsInterval = 300L

  private implicit val executionContext: ExecutionContext = context.dispatcher
  private implicit val system: ActorSystem = context.system
  private implicit val materializer: Materializer = ActorMaterializer()
  private implicit val timeout: Timeout = Timeout(10.seconds)

  private val oscillator = Oscillator.spawn("ocillator")

  private var currentTime = Option.empty[Long]
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

    case ListGauges(startingWith) =>
      val names = metricDatabase
        .gaugeNames
        .filter(_.startsWith(startingWith))
        .take(gaugeListLimit)
        .toVector

      sender() ! ListGauges.Reply(metricDatabase.gaugeNames.toVector)

    case GaugeEntries =>
      sender() ! GaugeEntries.Reply(metricDatabase.gaugeStream)

    case GaugeData(name) =>
      val data = metricDatabase.gaugeData(name)

      currentTime match {
        case Some(time) =>
          sender() ! GaugeData.Reply(window5m(time, data))

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
      currentTime = Some(value)

      for (i <- 1 to numberOfPeriods) {
        if (value % i == 0) {
          metricDatabase.rollUp(value, i)
        }
      }

      if (value % removeOldMetricsInterval == 0) {
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

case class GaugeEntry(when: Long, value: Long)

object MetricDatabase {
  case class Removed(gaugesRemoved: Int, gaugeEntriesRemoved: Int)
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
class MetricDatabase(maxGauges: Long, maxGaugeEntries: Long, maxGaugeLife: Long)(implicit mat: Materializer) {
  import MetricDatabase._

  private val events = mutable.HashMap.empty[Long, mutable.HashMap[String, Long]]
  private val gauges = mutable.HashMap.empty[String, mutable.TreeMap[Long, Long]]
  private val gaugesLastUpdated = mutable.HashMap.empty[String, Long]

  private val (gaugeEntrySink, gaugeEntrySource) =
    MergeHub.source[GaugeEntry]
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

  gaugeEntrySource.runWith(Sink.ignore)

  def gaugeData(name: String): Iterator[GaugeEntry] =
    gauges
      .get(name)
      .map(_.toIterator)
      .getOrElse(Iterator.empty)
      .map(GaugeEntry.tupled)

  def gaugeNames: Iterable[String] = gauges.keys

  def gaugeStream: Source[GaugeEntry, NotUsed] =
    gaugeEntrySource

  def ingestEvent(currentTime: Long, name: String, value: Long, rollUpPeriod: Long): Unit = {
    val entry = events.getOrElseUpdate(rollUpPeriod, mutable.HashMap.empty)
    entry.update(name, entry.getOrElse(name, 0L) + value)
  }

  /**
   * Adds a gauge value to the database. If a value is already stored for
   * the given gauge, the mean average of the currently stored value and
   * the provided value is stored.
   */
  def ingestGauge(currentTime: Long, name: String, value: Long): Unit = {
    val collection = gauges.getOrElseUpdate(name, mutable.TreeMap.empty)

    val gaugeEntry = collection.get(currentTime) match {
      case Some(existing) =>
        val newValue = (existing + value) / 2
        collection.update(currentTime, (existing + value) / 2)
        GaugeEntry(currentTime, newValue)
      case None =>
        collection.update(currentTime, value)
        GaugeEntry(currentTime, value)
    }

    Source.single(gaugeEntry).runWith(gaugeEntrySink)

    gaugesLastUpdated.update(name, currentTime)
  }

  def numberOfGaugeEntries: Long =
    gauges.foldLeft(0L)(_ + _._2.size)

  def removeOldMetrics(currentTime: Long): Removed = {
    var gaugeEntriesRemoved = 0
    var gaugesRemoved = 0

    def removeGauge(name: String): Unit = {
      gauges.remove(name)
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

    val oldestAllowed = currentTime - maxGaugeLife

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

  def rollUp(currentTime: Long, period: Long): Unit = {
    events.get(period).foreach { entries =>
      entries.foreach { case (name, value) =>
        ingestGauge(currentTime, name, value)
      }

      events.remove(period)
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
}

object UserInterface {
  def props(metricsCollector: ActorRef, host: String, port: Int): Props =
    Props(new UserInterface(metricsCollector, host, port))

  case object Stop

  private case object Unbound
}

class UserInterface private (metricsCollector: ActorRef, host: String, port: Int) extends Actor with ActorLogging with Stash {
  import UserInterface._
  import templates.Implicits._

  private implicit val executionContext: ExecutionContext = context.dispatcher
  private implicit val system: ActorSystem = context.system
  private implicit val materializer: Materializer = ActorMaterializer()
  private implicit val timeout: Timeout = Timeout(10.seconds)

  private object UIRoute extends JsonSupport {
    import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

    val route = concat(
      (get & pathEndOrSingleSlash) {
        complete(
          templates.listing()
        )
      },

      pathPrefix("api")(Route.seal(
        concat(
          (get & path("charts" / Segment)) { name =>
            val data = (metricsCollector ? MetricsCollector.GaugeData(name))
                .mapTo[MetricsCollector.GaugeData.Reply]

            complete(
              data.map(d =>
                ChartData(name, d.data.map { case GaugeEntry(k, v) => ChartEntry(k * 1000, v) })
              )
            )
          },
          (get & path("charts")) {
            val data = (metricsCollector ? MetricsCollector.ListGauges(""))
                .mapTo[MetricsCollector.ListGauges.Reply]

            complete(
              data.map(d =>
                ChartListing(d.names.map(Chart.apply))
              )
            )
          },
          (get & path("gauge-entries")) {
            val data = (metricsCollector ? MetricsCollector.GaugeEntries)
              .mapTo[MetricsCollector.GaugeEntries.Reply]

            complete(
              data.map(
                _
                  .source
                  .map(gaugeEntry => ChartEntry(gaugeEntry.when * 1000, gaugeEntry.value).toJson.compactPrint)
                  .map(data => ServerSentEvent(data, "GaugeEntry"))
                  .keepAlive(1.second, () => ServerSentEvent.heartbeat)
              )
            )
          }
        )
      )),

      pathPrefix("assets") {
        encodeResponseWith(Gzip) {
          getFromResourceDirectory("assets")
        }
      },

      pathPrefix("webjars") {
        encodeResponseWith(Gzip) {
          getFromResourceDirectory("META-INF/resources/webjars")
        }
      }
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
