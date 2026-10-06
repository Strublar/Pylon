package dev.pylon.cli

import dev.pylon.core._
import dev.pylon.indexer.Indexer
import dev.pylon.server.PylonServer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.{Try, Using}

object Main {

  private val usage =
    """pylon - interactive call-chain maps for Scala codebases
      |
      |Usage:
      |  pylon index [--db PATH] [--no-compile] [--semanticdb-version V] --service NAME=PATH [--service NAME=PATH ...]
      |  pylon query [--db PATH] [--all] (find|impls|callees|callers|paths) QUERY
      |  pylon map   [--db PATH] [--port N] [--up] [--no-open] QUERY   open the interactive map on QUERY
      |  pylon serve [--db PATH] [--port N] [--host H] [--static DIR]  serve the viewer without a starting point
      |  pylon endpoints [--db PATH] [--service S]                  list endpoints (HTTP, gRPC, Kafka) and their handlers
      |  pylon links [--db PATH] [--service S]                      list calls to other services and what they reach
      |  pylon services [--db PATH]
      |
      |QUERY is a symbol such as ProviderTrait.search, com.acme.ProviderA.search or a SemanticDB symbol,
      |or an endpoint such as "GET /api/items/{id}" (parameter names do not matter: "GET /api/items/42" works).
      |`map` opens the browser on the down view ("what does it call?"), or the up view with --up.
      |The graph is stored in .pylon/graph.db unless --db is given. sbt is taken from $PYLON_SBT or the PATH.
      |Cross-service links can be steered with pylon.json (or --links FILE):
      |  {"links": [{"hint": "catalog.url", "service": "catalog"}]}   a client whose base URL mentions the hint calls that service
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    // Arrows and dots in the output: do not depend on the platform's default console charset.
    System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8))
    System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8))
    val code = Console.withOut(System.out)(Console.withErr(System.err)(runSafely(args)))
    sys.exit(code)
  }

  private def runSafely(args: Array[String]): Int = {
    val code =
      try run(args.toList)
      catch {
        case e: UsageError => Console.err.println(s"error: ${e.getMessage}\n\n$usage"); 2
        case e: java.nio.file.NoSuchFileException => Console.err.println(s"error: no such file or directory: ${e.getMessage}"); 1
        case e: Exception                         => Console.err.println(s"error: ${e.getMessage}"); 1
      }
    code
  }

  final class UsageError(msg: String) extends RuntimeException(msg)

  /** Parsed `--flag value` options plus positional arguments. */
  private final case class Args(flags: Map[String, List[String]], switches: Set[String], positional: List[String]) {
    def db: Path = Paths.get(flags.get("--db").flatMap(_.lastOption).getOrElse(".pylon/graph.db"))
  }

  private val valueFlags = Set("--db", "--service", "--semanticdb-version", "--port", "--host", "--static", "--links")

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
    case "map" :: rest      => map(parseArgs(rest))
    case "serve" :: rest    => serve(parseArgs(rest), None)
    case "dump-semanticdb" :: root :: filter :: _ => dumpSemanticdb(Paths.get(root), filter)
    case "endpoints" :: rest => endpoints(parseArgs(rest))
    case "links" :: rest     => links(parseArgs(rest))
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
      semanticdbVersion = a.flags.get("--semanticdb-version").flatMap(_.lastOption),
      linkRules = linkRules(a)
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
                  println(s"  -> ${Format.callee(c)}${Format.link(c.link)}")
                  if (c.isFork) c.candidates.foreach(i => println(s"       | ${Format.symbol(i)}"))
                }
              case "callers" =>
                store.callers(target.symbol).foreach { c =>
                  val via = if (c.via.symbol == target.symbol) "" else s"  (via ${c.via.display})"
                  println(s"  <- ${Format.symbol(c.caller)}$via  @ ${Format.sites(c.sites)}${Format.link(c.link)}")
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

  private def map(a: Args): Int = {
    val q = a.positional.mkString(" ").trim
    if (q.isEmpty) throw new UsageError("map needs a symbol, e.g. pylon map ProviderTrait.search")
    val target = withStore(a)(resolve(_, q)).getOrElse(throw new IllegalArgumentException(s"no symbol matches '$q'"))
    val mode   = if (a.switches("--up")) "up" else "down"
    serve(a, Some(s"?sym=${URLEncoder.encode(target.symbol, StandardCharsets.UTF_8)}&mode=$mode"))
  }

  /** Serves the viewer until the process is killed; `start` is the page to open. */
  private def serve(a: Args, start: Option[String]): Int = {
    if (!Files.isRegularFile(a.db)) throw new IllegalArgumentException(s"${a.db} does not exist; run `pylon index` first")
    val store  = GraphStore.open(a.db)
    val host   = a.flags.get("--host").flatMap(_.lastOption).getOrElse("127.0.0.1")
    val port   = a.flags.get("--port").flatMap(_.lastOption).map(p => p.toIntOption.getOrElse(throw new UsageError(s"bad port $p"))).getOrElse(7777)
    val server = new PylonServer(store, host, port, a.flags.get("--static").flatMap(_.lastOption).map(Paths.get(_)))
    val bound  = server.start()
    val url    = s"http://${if (host == "0.0.0.0") "localhost" else host}:$bound/${start.getOrElse("")}"
    println(s"Pylon map: $url")
    println("Press Ctrl+C to stop.")
    if (start.isDefined && !a.switches("--no-open")) openBrowser(url)
    sys.addShutdownHook { server.stop(); store.close() }
    Thread.currentThread().join()
    0
  }

  private def openBrowser(url: String): Unit = {
    val viaDesktop = Try {
      java.awt.Desktop.isDesktopSupported && java.awt.Desktop.getDesktop.isSupported(java.awt.Desktop.Action.BROWSE) && {
        java.awt.Desktop.getDesktop.browse(java.net.URI.create(url)); true
      }
    }.getOrElse(false)
    if (!viaDesktop) {
      val opener = if (sys.props("os.name").toLowerCase.contains("mac")) "open" else "xdg-open"
      Try(new ProcessBuilder(opener, url).redirectErrorStream(true).start())
    }
  }

  /** Rules from `--links FILE` or `./pylon.json`: `{"links": [{"hint": "...", "service": "..."}]}`. */
  private def linkRules(a: Args): Seq[Linker.Rule] = {
    val file = a.flags.get("--links").flatMap(_.lastOption).map(Paths.get(_)).orElse(Some(Paths.get("pylon.json")).filter(Files.isRegularFile(_)))
    file.toSeq.flatMap { p =>
      val json = ujson.read(Files.readString(p))
      json.obj.get("links").toSeq.flatMap(_.arr).map(r => Linker.Rule(r("hint").str, r("service").str))
    }
  }

  private def links(a: Args): Int = withStore(a) { store =>
    val service = a.flags.get("--service").flatMap(_.lastOption)
    val byClient = store.links().groupBy(_.client)
    val clients = store.remotes(Some(Remote.Client)).filter(r => service.forall(_ == r.service))
    if (clients.isEmpty) Console.err.println("no calls to other services in the index")
    clients.groupBy(_.service).toSeq.sortBy(_._1).foreach { case (svc, list) =>
      println(svc)
      list.sortBy(_.symbol).foreach { r =>
        val node    = store.symbol(r.symbol)
        val callers = store.callers(r.symbol).map(_.caller.display).distinct.mkString(", ")
        println(s"  ${node.map(_.display).getOrElse(r.symbol)}  [${node.map(_.signature).getOrElse("")}]  from $callers  @ ${node.flatMap(_.file).getOrElse("")}:${node.flatMap(_.line).getOrElse(0)}")
        r.hint.foreach(h => println(s"      base: $h"))
        byClient.getOrElse(r.symbol, Nil).sortBy(-_.confidence).foreach { l =>
          val target = store.symbol(l.endpoint)
          println(f"      => ${target.flatMap(_.service).getOrElse("?")}%-10s ${target.map(_.display).getOrElse(l.endpoint)}  (${l.confidence}%.1f, ${l.reason})")
        }
        if (!byClient.contains(r.symbol)) println("      => (no matching endpoint indexed)")
      }
    }
    0
  }

  private def endpoints(a: Args): Int = withStore(a) { store =>
    val eps = store.endpoints(a.flags.get("--service").flatMap(_.lastOption))
    if (eps.isEmpty) Console.err.println("no endpoints in the index")
    eps.groupBy(_.service.getOrElse("")).toSeq.sortBy(_._1).foreach { case (svc, list) =>
      println(s"$svc")
      list.foreach { e =>
        val handlers = store.callees(e.symbol).filterNot(_.target.isExternal).map(_.target.display).take(3).mkString(", ")
        println(f"  ${e.display}%-40s ${s"[${e.signature}]"}%-13s -> $handlers%s   ${e.file.getOrElse("")}:${e.line.getOrElse(0)}")
      }
    }
    0
  }

  /** Debugging aid for adapter development: prints the occurrences of the documents whose uri contains `filter`. */
  private def dumpSemanticdb(root: Path, filter: String): Int = {
    dev.pylon.indexer.SemanticdbFiles.load(root.toAbsolutePath.normalize).filter(_.doc.uri.contains(filter)).foreach { d =>
      println(s"== ${d.doc.uri} (scala3=${d.isScala3})")
      d.doc.occurrences.sortBy(o => o.range.map(r => (r.startLine, r.startCharacter))).foreach { o =>
        val r = o.range.get
        println(f"  ${r.startLine + 1}%4d:${r.startCharacter + 1}%-3d ${if (o.role.isDefinition) "DEF" else "ref"} ${o.symbol}")
      }
      d.doc.synthetics.foreach(s => println(s"  synthetic ${s.range.map(r => s"${r.startLine + 1}:${r.startCharacter + 1}").getOrElse("")} ${s.tree}"))
    }
    0
  }

  /** Picks the best match, preferring project methods, and says so when the query is ambiguous. */
  def resolve(store: GraphStore, q: String): Option[SymbolNode] = {
    val matches = store.find(q, limit = 5)
    matches.headOption.foreach { best =>
      val others = matches.tail.filter(m => m.display == best.display && m.kind == best.kind)
      if (others.nonEmpty) {
        val hint =
          if (best.kind == SymbolKind.Endpoint) s"others: ${others.map(o => s"${o.display} [${o.signature}] = ${o.symbol}").mkString("; ")}"
          else s"qualify it with its package, e.g. ${others.head.symbol.replace('/', '.').replace('#', '.').stripSuffix("().").stripSuffix(".")}"
        Console.err.println(
          s"note: '$q' matches ${others.size + 1} symbols; using ${best.symbol} (${best.service.getOrElse("external")}). " +
            s"To pick another, pass its full symbol; $hint"
        )
      }
    }
    matches.headOption
  }
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
    if (s.kind == SymbolKind.Endpoint) s"endpoint ${s.display} [${s.signature}]$where"
    else if (s.kind == SymbolKind.Client) s"client ${s.display} [${s.signature}]$where"
    else s"$abs${s.kind.id} ${s.display}${s.signature}$where"
  }

  def link(l: Option[Link]): String = l.fold("")(l => f"  (link ${l.confidence}%.1f: ${l.reason})")

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
