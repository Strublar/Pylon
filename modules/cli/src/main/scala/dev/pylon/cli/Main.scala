package dev.pylon.cli

import dev.pylon.core._
import dev.pylon.indexer.Indexer
import java.nio.file.{Path, Paths}
import scala.util.Using

object Main {

  private val usage =
    """pylon - interactive call-chain maps for Scala codebases
      |
      |Usage:
      |  pylon index [--db PATH] [--no-compile] [--semanticdb-version V] --service NAME=PATH [--service NAME=PATH ...]
      |  pylon query [--db PATH] [--all] (find|impls|callees|callers|paths) QUERY
      |  pylon services [--db PATH]
      |
      |QUERY is a symbol such as ProviderTrait.search, com.acme.ProviderA.search or a SemanticDB symbol.
      |The graph is stored in .pylon/graph.db unless --db is given. sbt is taken from $PYLON_SBT or the PATH.
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    val code =
      try run(args.toList)
      catch {
        case e: UsageError => Console.err.println(s"error: ${e.getMessage}\n\n$usage"); 2
        case e: java.nio.file.NoSuchFileException => Console.err.println(s"error: no such file or directory: ${e.getMessage}"); 1
        case e: Exception                         => Console.err.println(s"error: ${e.getMessage}"); 1
      }
    sys.exit(code)
  }

  final class UsageError(msg: String) extends RuntimeException(msg)

  /** Parsed `--flag value` options plus positional arguments. */
  private final case class Args(flags: Map[String, List[String]], switches: Set[String], positional: List[String]) {
    def db: Path = Paths.get(flags.get("--db").flatMap(_.lastOption).getOrElse(".pylon/graph.db"))
  }

  private val valueFlags = Set("--db", "--service", "--semanticdb-version", "--port")

  private def parseArgs(args: List[String]): Args = {
    def go(rest: List[String], acc: Args): Args = rest match {
      case flag :: value :: tail if valueFlags(flag) =>
        go(tail, acc.copy(flags = acc.flags.updated(flag, acc.flags.getOrElse(flag, Nil) :+ value)))
      case flag :: Nil if valueFlags(flag) => throw new UsageError(s"$flag needs a value")
      case flag :: tail if flag.startsWith("--") => go(tail, acc.copy(switches = acc.switches + flag))
      case arg :: tail                           => go(tail, acc.copy(positional = acc.positional :+ arg))
      case Nil                                   => acc
    }
    go(args, Args(Map.empty, Set.empty, Nil))
  }

  def run(args: List[String]): Int = args match {
    case "index" :: rest    => index(parseArgs(rest))
    case "query" :: rest    => query(parseArgs(rest))
    case "services" :: rest => withStore(parseArgs(rest)) { s => s.services.foreach { case (n, r) => println(s"$n\t$r") }; 0 }
    case ("help" | "--help" | "-h") :: _ => println(usage); 0
    case Nil                => println(usage); 0
    case other :: _         => throw new UsageError(s"unknown command '$other'")
  }

  private def withStore[A](a: Args)(f: GraphStore => A): A = Using.resource(GraphStore.open(a.db))(f)

  private def index(a: Args): Int = {
    val services = a.flags.getOrElse("--service", Nil).map { spec =>
      spec.split("=", 2) match {
        case Array(name, path) if name.nonEmpty && path.nonEmpty => name -> Paths.get(path)
        case _ => throw new UsageError(s"--service expects NAME=PATH, got '$spec'")
      }
    }
    if (services.isEmpty) throw new UsageError("index needs at least one --service NAME=PATH")
    val options = Indexer.Options(
      compile = !a.switches("--no-compile"),
      semanticdbVersion = a.flags.get("--semanticdb-version").flatMap(_.lastOption)
    )
    withStore(a) { store =>
      services.foreach { case (name, path) => Indexer.index(store, name, path, options) }
    }
    Console.err.println(s"[pylon] graph written to ${a.db}")
    0
  }

  private def query(a: Args): Int = {
    val (cmd, q) = a.positional match {
      case c :: rest if rest.nonEmpty => c -> rest.mkString(" ")
      case _                          => throw new UsageError("query needs a command and a symbol")
    }
    val showExternal = a.switches("--all")
    withStore(a) { store =>
      if (cmd == "find") {
        store.find(q).foreach(s => println(Format.symbol(s)))
        0
      } else
        resolve(store, q) match {
          case None => Console.err.println(s"no symbol matches '$q'"); 1
          case Some(target) =>
            println(Format.symbol(target))
            cmd match {
              case "impls" =>
                val impls = if (target.kind == SymbolKind.Method) store.implementations(target.symbol) else store.subtypes(target.symbol)
                impls.foreach(s => println(s"  ${Format.symbol(s)}"))
              case "callees" =>
                store.callees(target.symbol).filter(c => showExternal || !c.target.isExternal).foreach { c =>
                  println(s"  -> ${Format.callee(c)}")
                  if (c.isFork) c.candidates.foreach(i => println(s"       | ${Format.symbol(i)}"))
                }
              case "callers" =>
                store.callers(target.symbol).foreach { c =>
                  val via = if (c.via.symbol == target.symbol) "" else s"  (via ${c.via.display})"
                  println(s"  <- ${Format.symbol(c.caller)}$via  @ ${Format.sites(c.sites)}")
                }
              case "paths" =>
                store.entrypointPaths(target.symbol).foreach { path =>
                  println("  " + path.map(step => step.via.filter(_.symbol != step.node.symbol).fold(step.node.display)(v => s"${step.node.display} [via ${v.display}]")).mkString(" -> "))
                }
              case other => throw new UsageError(s"unknown query '$other'")
            }
            0
        }
    }
  }

  /** Picks the best match, preferring project methods. */
  def resolve(store: GraphStore, q: String): Option[SymbolNode] = store.find(q, limit = 5).headOption
}

object Format {
  def symbol(s: SymbolNode): String = {
    val where = (s.service, s.file, s.line) match {
      case (Some(svc), Some(f), Some(l)) => s"  [$svc] $f:$l"
      case (Some(svc), Some(f), None)    => s"  [$svc] $f"
      case (None, _, _)                  => "  [external]"
      case _                             => ""
    }
    val abs = if (s.isAbstract && s.kind == SymbolKind.Method) "abstract " else ""
    s"$abs${s.kind.id} ${s.display}${s.signature}$where"
  }

  def sites(sites: Seq[(String, Int)]): String = sites.map { case (f, l) => s"$f:$l" }.mkString(", ")

  def callee(c: Callee): String = {
    val tags = Seq(
      if (c.isFork) Some(s"fork: ${c.candidates.size} implementations") else None,
      c.soleImplementation.map(i => s"only implementation: ${i.display}"),
      if (c.synthetic) Some("implicit") else None,
      if (c.target.isExternal) Some("external") else None
    ).flatten
    val tagStr = if (tags.isEmpty) "" else tags.mkString("  {", "; ", "}")
    s"${c.target.display}${c.target.signature}$tagStr  @ ${sites(c.sites)}"
  }
}
