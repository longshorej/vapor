import ReleaseTransformations._

lazy val vapor = project.in(file("."))
  .settings(
    name := "vapor",

    scalaVersion in ThisBuild := Versions.scala,

    libraryDependencies ++= Vector(
      "com.typesafe.akka"          %% "akka-actor"           % Versions.akka,
      "com.typesafe.akka"          %% "akka-http-spray-json" % Versions.akkaHttp,
      "com.typesafe.akka"          %% "akka-stream"          % Versions.akka
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

unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "frontend" / "dist"

lazy val Versions = new {
  val akka             = "2.5.19"
  val akkaHttp         = "10.1.6"
  val scala            = "2.12.8"
}

resolvers += Resolver.typesafeRepo("releases")
