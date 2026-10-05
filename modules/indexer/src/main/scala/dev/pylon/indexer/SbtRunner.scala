package dev.pylon.indexer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.sys.process._

/**
 * Compiles an sbt build with SemanticDB enabled, without modifying the build.
 *
 * Like Metals/Bloop, Pylon passes `--addPluginSbtFile`: the extra meta-build file compiles a
 * tiny auto-plugin (written to a temp dir) that enables SemanticDB in every project. A plugin is
 * needed rather than `set ...` because Scala 2 needs `-P:semanticdb:synthetics:on` (implicit and
 * for-comprehension calls) while Scala 3 rejects that option, and only a per-project setting can
 * look at each project's own Scala version.
 */
object SbtRunner {

  /**
   * The sbt command to run: `$PYLON_SBT` when set, else `sbt` from the PATH.
   * Pylon's own `bin/sbt` works as a drop-in when sbt is not installed.
   */
  def sbtCommand(): Seq[String] =
    sys.env.get("PYLON_SBT").filter(_.nonEmpty).map(Seq(_)).getOrElse(Seq("sbt"))

  def pluginSource(semanticdbVersion: Option[String]): String = {
    val versionSetting = semanticdbVersion.fold("")(v => s""",\n    semanticdbVersion := "$v"""")
    s"""import sbt._
       |import sbt.Keys._
       |
       |object PylonSemanticdbPlugin extends AutoPlugin {
       |  override def trigger = allRequirements
       |  override def requires = sbt.plugins.SemanticdbPlugin
       |  override def projectSettings: Seq[Setting[_]] = Seq(
       |    semanticdbEnabled := true,
       |    semanticdbOptions ++= {
       |      if (scalaBinaryVersion.value == "3") Nil else List("-P:semanticdb:synthetics:on")
       |    }$versionSetting
       |  )
       |}
       |""".stripMargin
  }

  /** Writes the plugin sources and returns the `.sbt` file to pass to `--addPluginSbtFile`. */
  def writePlugin(semanticdbVersion: Option[String]): Path = {
    val dir = Files.createTempDirectory("pylon-sbt")
    val src = Files.createDirectories(dir.resolve("src"))
    Files.write(src.resolve("PylonSemanticdbPlugin.scala"), pluginSource(semanticdbVersion).getBytes(StandardCharsets.UTF_8))
    val sbtFile = dir.resolve("pylon.sbt")
    val srcPath = src.toAbsolutePath.toString.replace("\\", "\\\\").replace("\"", "\\\"")
    Files.write(sbtFile, s"""Compile / unmanagedSourceDirectories += file("$srcPath")\n""".getBytes(StandardCharsets.UTF_8))
    sbtFile
  }

  final case class Failed(exitCode: Int, tail: Seq[String])
      extends RuntimeException(s"sbt exited with code $exitCode:\n${tail.mkString("\n")}")

  def compile(root: Path, semanticdbVersion: Option[String] = None, log: String => Unit = Console.err.println): Unit = {
    require(
      Files.isRegularFile(root.resolve("build.sbt")) || Files.isDirectory(root.resolve("project")),
      s"$root is not an sbt build"
    )
    val plugin = writePlugin(semanticdbVersion)
    val cmd    = sbtCommand() ++ Seq(s"--addPluginSbtFile=$plugin", "Test/compile")
    log(s"[pylon] ${root.getFileName}: compiling with SemanticDB (${cmd.mkString(" ")})")
    val tail = scala.collection.mutable.Queue.empty[String]
    val logger = ProcessLogger { line =>
      tail.enqueue(line)
      if (tail.size > 40) tail.dequeue()
      if (line.contains("[error]") || line.contains("compiling")) log(s"  $line")
    }
    val exit = Process(cmd, root.toFile).run(logger, connectInput = false).exitValue()
    if (exit != 0) throw Failed(exit, tail.toSeq)
  }
}
