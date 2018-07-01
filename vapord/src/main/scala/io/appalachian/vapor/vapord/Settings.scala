package io.appalachian.vapor.vapord

import akka.actor._

final class Settings(system: ExtendedActorSystem) extends Extension {
  private val vapord = system.settings.config.getConfig("vapord")

  val metricsBindHost: String = vapord.getString("metrics-bind-host")
  val metricsBindPort: Int = vapord.getInt("metrics-bind-port")
  val uiBindHost: String = vapord.getString("ui-bind-host")
  val uiBindPort: Int = vapord.getInt("ui-bind-port")
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {
  override def get(system: ActorSystem): Settings = super.get(system)

  override def lookup: Settings.type = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = new Settings(system)
}
