ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "com.acme"

lazy val api = project

lazy val core = project.dependsOn(api)

lazy val app = project.dependsOn(core)

lazy val root = (project in file("."))
  .aggregate(api, core, app)
  .settings(name := "search-3")
