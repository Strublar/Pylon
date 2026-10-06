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

  /**
   * Latest `semanticdb-scalac` release published for each Scala 2 version (from Maven Central).
   * sbt's built-in default is often too old for recent Scala patch releases, or too new for old ones.
   */
  val semanticdbScalacVersions: Map[String, String] =
    "2.12.3 2.1.3;2.12.4 4.1.9;2.12.5 4.1.9;2.12.6 4.1.9;2.12.7 4.2.3;2.12.8 4.5.13;2.12.9 4.8.4;2.12.10 4.8.4;2.12.11 4.8.4;2.12.12 4.8.4;2.12.13 4.8.4;2.12.14 4.8.4;2.12.15 4.9.0;2.12.16 4.9.9;2.12.17 4.14.2;2.12.18 4.17.4;2.12.19 4.17.4;2.12.20 4.17.4;2.12.21 4.17.4;2.13.0 4.6.0;2.13.2 4.8.4;2.13.3 4.8.4;2.13.4 4.8.4;2.13.5 4.8.4;2.13.6 4.8.4;2.13.7 4.8.4;2.13.8 4.8.10;2.13.9 4.9.0;2.13.10 4.9.3;2.13.11 4.9.9;2.13.12 4.12.3;2.13.13 4.13.10;2.13.14 4.14.1;2.13.15 4.17.4;2.13.16 4.17.4;2.13.17 4.17.4;2.13.18 4.17.4"
      .split(';')
      .map(_.split(' ') match { case Array(scala, sdb) => scala -> sdb })
      .toMap

  /** Version for Scala versions newer than the table: the newest known release. */
  val latestSemanticdbScalac: String = "4.17.4"

  def pluginSource(semanticdbVersion: Option[String]): String = {
    val q     = "\""
    val table = semanticdbScalacVersions.toSeq.sorted.map { case (k, v) => s"$q$k$q -> $q$v$q" }.mkString(", ")
    val versionSetting = semanticdbVersion match {
      case Some(v) => s"""semanticdbVersion := "$v""""
      case None =>
        s"""semanticdbVersion := {
           |      val known = Map[String, String]($table)
           |      val v = scalaVersion.value
           |      def patch(s: String) = scala.util.Try(s.split('.')(2).takeWhile(_.isDigit).toInt).getOrElse(-1)
           |      known.get(v).getOrElse {
           |        val series = known.keys.filter(_.startsWith(v.split('.').take(2).mkString(".") + "."))
           |        if (series.nonEmpty && patch(v) > series.map(patch).max) "$latestSemanticdbScalac"
           |        else semanticdbVersion.value
           |      }
           |    }""".stripMargin
    }
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
       |    },
       |    $versionSetting
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
    val process =
      try Process(cmd, root.toFile).run(logger, connectInput = false)
      catch {
        case e: java.io.IOException =>
          throw new IllegalStateException(
            s"could not run '${cmd.head}' (${e.getMessage}). Install sbt, set PYLON_SBT to an sbt executable, " +
              "or use Pylon's bin/pylon launcher, which falls back to bin/sbt."
          )
      }
    val exit = process.exitValue()
    if (exit != 0) throw Failed(exit, tail.toSeq)
  }
}
