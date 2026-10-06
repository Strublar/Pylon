ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "dev.pylon"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Xsource:3")

val scalametaVersion = "4.17.4"

lazy val commonSettings = Seq(
  libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
  Test / fork := true,
  Test / javaOptions += s"-Dpylon.repoRoot=${(ThisBuild / baseDirectory).value}",
  Test / envVars ++= sys.env.get("PYLON_SBT").fold(Map("PYLON_SBT" -> s"${(ThisBuild / baseDirectory).value}/bin/sbt"))(_ => Map.empty)
)

lazy val core = (project in file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "pylon-core",
    libraryDependencies += "org.xerial" % "sqlite-jdbc" % "3.53.4.0"
  )

lazy val indexer = (project in file("modules/indexer"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "pylon-indexer",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % scalametaVersion,
      "org.scalameta" %% "semanticdb-shared" % scalametaVersion
    )
  )

lazy val server = (project in file("modules/server"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "pylon-server",
    libraryDependencies += "com.lihaoyi" %% "upickle" % "4.4.3"
  )

lazy val cli = (project in file("modules/cli"))
  .dependsOn(indexer, server)
  .settings(commonSettings)
  .settings(
    name := "pylon-cli",
    Compile / mainClass := Some("dev.pylon.cli.Main"),
    run / fork := true,
    run / connectInput := true,
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    assembly / mainClass := Some("dev.pylon.cli.Main"),
    assembly / assemblyJarName := "pylon.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "versions", _ @_*)     => MergeStrategy.first
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) => MergeStrategy.discard
      case "module-info.class"                         => MergeStrategy.discard
      case x =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    }
  )

lazy val root = (project in file("."))
  .aggregate(core, indexer, server, cli)
  .settings(name := "pylon", publish / skip := true)
