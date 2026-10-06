ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "com.acme"

lazy val root = (project in file("."))
  .settings(
    name := "catalog",
    Compile / PB.targets := Seq(scalapb.gen(flatPackage = true, grpc = true) -> (Compile / sourceManaged).value / "scalapb"),
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb"         %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "org.http4s"                   %% "http4s-dsl"           % "0.23.38",
      "org.http4s"                   %% "http4s-server"        % "0.23.38",
      "com.softwaremill.sttp.tapir"  %% "tapir-core"           % "1.13.32",
      "com.softwaremill.sttp.tapir"  %% "tapir-http4s-server"  % "1.13.32",
      "org.apache.kafka"              % "kafka-clients"        % "3.9.1",
      "com.github.fd4s"              %% "fs2-kafka"            % "3.9.1"
    )
  )
