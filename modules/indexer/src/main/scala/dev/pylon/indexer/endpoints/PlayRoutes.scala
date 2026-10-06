package dev.pylon.indexer.endpoints

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.meta.internal.semanticdb.{MethodSignature, SymbolInformation}
import scala.util.Using

/**
 * Play `conf/routes` and `conf/<name>.routes` files.
 *
 * Each file is a pseudo-holder (`play-routes:<conf dir>/<router package>`), so `-> /prefix sub.Routes`
 * includes become explicit mounts. Actions are linked by direct edges to the controller methods.
 */
object PlayRoutes {

  val Framework = "play"

  final case class Line(lineNo: Int, content: Entry)
  sealed trait Entry
  final case class RouteLine(verb: String, segments: Seq[String], controller: String, method: String, arity: Option[Int]) extends Entry
  final case class Include(prefix: Seq[String], router: String) extends Entry

  private val Verbs = Set("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

  /** Parses a routes file. Comments, blank lines and `+ modifier` lines are skipped. */
  def parse(text: String): Seq[Line] =
    text.linesIterator.zipWithIndex.flatMap { case (raw, i) =>
      val line = raw.trim
      if (line.isEmpty || line.startsWith("#") || line.startsWith("+")) None
      else
        line.split("\\s+", 3).toList match {
          case "->" :: prefix :: router :: Nil =>
            Some(Line(i + 1, Include(pathSegments(prefix), router.trim.stripSuffix(".Routes"))))
          case verb :: path :: call :: Nil if Verbs(verb) =>
            parseCall(call.trim).map { case (controller, method, arity) =>
              Line(i + 1, RouteLine(verb, pathSegments(path), controller, method, arity))
            }
          case _ => None
        }
    }.toSeq

  // `/items/:id`, `/files/ *path`, `/legacy/$id<[0-9]+>` -> `items/{id}`, `files/{*path}`, `legacy/{id}`.
  def pathSegments(path: String): Seq[String] =
    path.split('/').toSeq.filter(_.nonEmpty).map {
      case s if s.startsWith(":") => s"{${s.drop(1)}}"
      case s if s.startsWith("*") => s"{*${s.drop(1)}}"
      case s if s.startsWith("$") => s"{${s.drop(1).takeWhile(_ != '<')}}"
      case s                      => s
    }

  /** `controllers.X.show(id: Long)` -> (`controllers.X`, `show`, Some(1)); `@` (injected) prefixes are dropped. */
  def parseCall(call: String): Option[(String, String, Option[Int])] = {
    val c        = call.stripPrefix("@")
    val name     = c.takeWhile(_ != '(').trim
    val argsPart = if (c.contains('(')) Some(c.substring(c.indexOf('(') + 1, c.lastIndexOf(')').max(c.indexOf('(') + 1))) else None
    val arity    = argsPart.map(a => if (a.trim.isEmpty) 0 else topLevelCommas(a) + 1)
    val dot      = name.lastIndexOf('.')
    if (dot <= 0) None else Some((name.substring(0, dot), name.substring(dot + 1), arity))
  }

  private def topLevelCommas(s: String): Int = {
    var depth = 0
    var n     = 0
    s.foreach {
      case '(' | '[' => depth += 1
      case ')' | ']' => depth -= 1
      case ',' if depth == 0 => n += 1
      case _ =>
    }
    n
  }

  /** Finds the routes files of every Play project under `root`. */
  def files(root: Path): Seq[Path] =
    Using.resource(Files.walk(root)) { s =>
      s.iterator.asScala
        .filter(Files.isRegularFile(_))
        .filter { p =>
          val rel = root.relativize(p).toString.split('/')
          !rel.exists(Set("target", "node_modules", ".git")) && rel.length >= 2 && rel(rel.length - 2) == "conf" &&
          (rel.last == "routes" || rel.last.endsWith(".routes"))
        }
        .toVector
        .sorted
    }

  /** The router package a routes file generates: `routes` -> `router`, `admin.routes` -> `admin`. */
  private def routerOf(file: Path): String = file.getFileName.toString match {
    case "routes" => "router"
    case n        => n.stripSuffix(".routes")
  }

  private def holderId(conf: Path, router: String): String = s"play-routes:$conf/$router"

  def scan(root: Path, symtab: Map[String, SymbolInformation], warn: String => Unit): Found =
    files(root).foldLeft(Found()) { (acc, file) =>
      val conf   = file.getParent
      val holder = holderId(root.relativize(conf), routerOf(file))
      val rel    = root.relativize(file).toString
      val lines  = parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
      val found = lines.foldLeft(Found()) {
        case (f, Line(n, RouteLine(verb, segs, controller, method, arity))) =>
          resolveAction(controller, method, arity, symtab) match {
            case Some(target) =>
              f.copy(routes = f.routes :+ Route(Framework, verb, segs, Some(holder), rel, n, n, targets = Seq(target)))
            case None =>
              warn(s"[pylon] $rel:$n: no method $controller.$method found in the index")
              f.copy(routes = f.routes :+ Route(Framework, verb, segs, Some(holder), rel, n, n))
          }
        case (f, Line(_, Include(prefix, router))) =>
          f.copy(mounts = f.mounts :+ Mount(holder, prefix, holderId(root.relativize(conf), router), explicit = true))
      }
      acc ++ found
    }

  /** `controllers.X` + `show` -> the class (injected controller) or object method, picking overloads by arity. */
  def resolveAction(controller: String, method: String, arity: Option[Int], symtab: Map[String, SymbolInformation]): Option[String] = {
    val owner      = controller.replace('.', '/')
    val candidates = Seq(s"$owner#", s"$owner.").flatMap { o =>
      symtab.keysIterator.filter(s => s.startsWith(s"$o$method(") && s.endsWith(").")).toSeq.sorted
    }
    def params(sym: String): Option[Int] = symtab.get(sym).map(_.signature).collect {
      case m: MethodSignature => m.parameterLists.headOption.map(s => s.symlinks.size + s.hardlinks.size).getOrElse(0)
    }
    arity.flatMap(a => candidates.find(c => params(c).contains(a))).orElse(candidates.headOption)
  }
}
