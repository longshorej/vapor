package com.jasonlongshore.vapor

import akka.actor._

final class Settings(system: ExtendedActorSystem) extends Extension {
  private val vapor = system.settings.config.getConfig("vapor")

  val metricsBindHost: String = vapor.getString("metrics-bind-host")
  val metricsBindPort: Int = vapor.getInt("metrics-bind-port")
  val uiBindHost: String = vapor.getString("ui-bind-host")
  val uiBindPort: Int = vapor.getInt("ui-bind-port")
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {
  override def get(system: ActorSystem): Settings = super.get(system)

  override def lookup: Settings.type = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = new Settings(system)
}
