import ReleaseTransformations._

val vapord = Project("vapord", file("."))
  .settings(
    name := "vapord",

    scalaVersion in ThisBuild := Versions.scala,

    libraryDependencies ++= Vector(
      "com.lihaoyi"                %% "scalatags"            % Versions.scalaTags,
      "com.typesafe.akka"          %% "akka-actor"           % Versions.akka,
      "com.typesafe.akka"          %% "akka-http-spray-json" % Versions.akkaHttp,
      "com.typesafe.akka"          %% "akka-stream"          % Versions.akka,
      "org.webjars.npm"            %  "jquery"               % Versions.jquery,
      "org.webjars.npm"            %  "morris.js"            % Versions.morrisJs,
      "org.webjars.npm"            %  "raphael"              % Versions.raphael,
    ),

    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

val Versions = new {
  val akka             = "2.5.13"
  val akkaHttp         = "10.1.3"
  val argonaut         = "6.2"
  val jquery           = "3.3.1"
  val logback          = "1.2.3"
  val morrisJs         = "0.5.0"
  val raphael          = "2.2.7"
  val scala            = "2.12.6"
  val scalaLogging     = "3.9.0"
  val scalaTags        = "0.6.7"
}

resolvers += Resolver.typesafeRepo("releases")
