name := "director-v2"
organization := "io.github.uptane"
scalaVersion := "2.13.10"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-feature", "-Xlog-reflective-calls", "-Xasync", "-Xsource:3", "-Ywarn-unused")

resolvers += "sonatype-snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"
resolvers += "sonatype-releases" at "https://s01.oss.sonatype.org/content/repositories/releases"

Global / bloopAggregateSourceDependencies := true

libraryDependencies ++= {
  val akkaV = "2.6.20"
  val akkaHttpV = "10.2.10"
  val scalaTestV = "3.2.18"
  val bouncyCastleV = "1.77"
  val tufV = "3.0.0"
  val libatsV = "2.3.1"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "org.scalatest"     %% "scalatest" % scalaTestV % Test,
    "org.scalacheck" %% "scalacheck" % "1.17.0" % Test,

    "io.github.uptane" %% "libats" % libatsV,
    "io.github.uptane" %% "libats-messaging" % libatsV,
    "io.github.uptane" %% "libats-messaging-datatype" % libatsV,
    "io.github.uptane" %% "libats-metrics-akka" % libatsV,
    "io.github.uptane" %% "libats-metrics-prometheus" % libatsV,
    "io.github.uptane" %% "libats-http-tracing" % libatsV,
    "io.github.uptane" %% "libats-slick" % libatsV,
    "io.github.uptane" %% "libats-logging" % libatsV,
    "io.github.uptane" %% "libtuf" % tufV,
    "io.github.uptane" %% "libtuf-server" % tufV,

    "org.bouncycastle" % "bcprov-jdk18on" % bouncyCastleV,
    "org.bouncycastle" % "bcpkix-jdk18on" % bouncyCastleV,

    "org.scala-lang.modules" %% "scala-async" % "1.0.1",
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided,

    "org.mariadb.jdbc" % "mariadb-java-client" % "3.3.3"
  )
}

Test / testOptions ++= Seq(
  Tests.Argument(TestFrameworks.ScalaTest, "-u", "target/test-reports"),
  Tests.Argument(TestFrameworks.ScalaTest, "-oDS")
)

buildInfoObject := "AppBuildInfo"
buildInfoPackage := "com.advancedtelematic.director"
buildInfoUsePackageAsPath := true
buildInfoOptions += BuildInfoOption.Traits("com.advancedtelematic.libats.boot.VersionInfoProvider")
buildInfoOptions += BuildInfoOption.ToMap
buildInfoOptions += BuildInfoOption.BuildTime

enablePlugins(BuildInfoPlugin, GitVersioning, JavaAppPackaging)

Compile / mainClass := Some("com.advancedtelematic.director.Boot")

dockerRepository := Some("advancedtelematic")

Docker / packageName := packageName.value

dockerUpdateLatest := true

dockerAliases ++= Seq(dockerAlias.value.withTag(git.gitHeadCommit.value))

Docker / defaultLinuxInstallLocation := s"/opt/${moduleName.value}"

dockerBaseImage := "eclipse-temurin:17.0.3_7-jre-jammy"

Docker / daemonUser := "daemon"

fork := true
