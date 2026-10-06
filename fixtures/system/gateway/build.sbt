ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "com.acme"

lazy val root = (project in file("."))
  .enablePlugins(Fs2Grpc)
  .settings(
    name := "gateway",
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"                 % "4.0.27",
      "com.softwaremill.sttp.tapir"   %% "tapir-core"           % "1.13.32",
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client4"   % "1.13.32",
      "org.http4s"                    %% "http4s-dsl"           % "0.23.38",
      "org.http4s"                    %% "http4s-server"        % "0.23.38",
      "org.http4s"                    %% "http4s-client"        % "0.23.38",
      "org.apache.pekko"              %% "pekko-http"           % "1.4.0",
      "org.apache.pekko"              %% "pekko-stream"         % "1.1.5",
      "org.playframework"             %% "play-ahc-ws-standalone" % "3.0.14",
      "org.apache.kafka"               % "kafka-clients"        % "3.9.1",
      "com.typesafe"                   % "config"               % "1.4.9"
    )
  )
