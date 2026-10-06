// One subproject per HTTP framework. Each routes into a small SearchService trait
// with two implementations, so a walk from any endpoint meets a fork right away.
ThisBuild / organization := "com.acme"

val scala213 = "2.13.18"
val scala3   = "3.3.7"

lazy val play = (project in file("play"))
  .enablePlugins(PlayScala)
  .settings(scalaVersion := scala213)

lazy val http4s = project.settings(
  scalaVersion := scala3,
  libraryDependencies ++= Seq(
    "org.http4s" %% "http4s-dsl"    % "0.23.38",
    "org.http4s" %% "http4s-server" % "0.23.38"
  )
)

lazy val tapir = project.settings(
  scalaVersion := scala3,
  libraryDependencies ++= Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-core"          % "1.13.32",
    "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % "1.13.32"
  )
)

lazy val ziohttp = project.settings(
  scalaVersion := scala3,
  libraryDependencies += "dev.zio" %% "zio-http" % "3.11.6"
)

lazy val pekko = project.settings(
  scalaVersion := scala3,
  libraryDependencies ++= Seq(
    "org.apache.pekko" %% "pekko-http"   % "1.4.0",
    "org.apache.pekko" %% "pekko-stream" % "1.1.5"
  )
)

lazy val akka = project.settings(
  scalaVersion := scala213,
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http"   % "10.2.10",
    "com.typesafe.akka" %% "akka-stream" % "2.6.21"
  )
)

lazy val root = (project in file("."))
  .aggregate(play, http4s, tapir, ziohttp, pekko, akka)
  .settings(name := "web", scalaVersion := scala213)
