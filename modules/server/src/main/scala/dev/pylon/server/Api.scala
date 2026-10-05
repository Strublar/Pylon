package dev.pylon.server

import dev.pylon.core._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * JSON API used by the viewer. Pure request -> response so it can be tested without a socket.
 *
 * Symbols contain `/`, `#` and backticks, so they are always passed as the `sym` query parameter.
 */
final class Api(store: GraphStore) {
  import Api.Response

  private def ok(v: ujson.Value)       = Response(200, v)
  private def notFound(msg: String)    = Response(404, ujson.Obj("error" -> msg))
  private def badRequest(msg: String)  = Response(400, ujson.Obj("error" -> msg))

  def handle(path: String, params: Map[String, String]): Response = {
    def withSymbol(f: SymbolNode => Response): Response =
      params.get("sym").filter(_.nonEmpty) match {
        case None      => badRequest("missing 'sym' parameter")
        case Some(sym) => store.symbol(sym).fold(notFound(s"unknown symbol $sym"))(f)
      }

    path.stripSuffix("/") match {
      case "/api/services" =>
        ok(ujson.Arr.from(store.services.map { case (n, r) => ujson.Obj("name" -> n, "root" -> r) }))

      case "/api/search" =>
        val q = params.getOrElse("q", "").trim
        if (q.isEmpty) ok(ujson.Arr())
        else ok(ujson.Arr.from(store.find(q, limit = params.get("limit").flatMap(_.toIntOption).getOrElse(20)).map(Json.node)))

      case "/api/node" =>
        withSymbol { node =>
          val isType = Set[SymbolKind](SymbolKind.Trait, SymbolKind.Class, SymbolKind.Object)(node.kind)
          ok(
            ujson.Obj(
              "node"            -> Json.node(node),
              "implementations" -> ujson.Arr.from(
                if (node.kind == SymbolKind.Method) store.implementations(node.symbol).map(Json.node) else Nil
              ),
              "overrides" -> ujson.Arr.from(
                if (node.kind == SymbolKind.Method) store.overridden(node.symbol).map(Json.node) else Nil
              ),
              "subtypes" -> ujson.Arr.from(if (isType) store.subtypes(node.symbol).map(Json.node) else Nil),
              "members"  -> ujson.Arr.from(if (isType) store.members(node.symbol).map(Json.node) else Nil)
            )
          )
        }

      case "/api/callees" =>
        withSymbol { node =>
          val external = params.get("external").contains("1")
          ok(ujson.Arr.from(store.callees(node.symbol).filter(c => external || !c.target.isExternal).map(Json.callee)))
        }

      case "/api/callers" =>
        withSymbol(node => ok(ujson.Arr.from(store.callers(node.symbol).map(Json.caller))))

      case "/api/paths" =>
        withSymbol { node =>
          val paths = store.entrypointPaths(node.symbol, maxPaths = params.get("max").flatMap(_.toIntOption).getOrElse(50))
          ok(ujson.Arr.from(paths.map(p => ujson.Arr.from(p.map(Json.step)))))
        }

      case "/api/source" =>
        withSymbol(node => source(node).fold(notFound(s"no source for ${node.display}"))(ok))

      case other => notFound(s"unknown endpoint $other")
    }
  }

  /** Source lines of a definition, read from its service root. Never reads outside indexed roots. */
  private def source(node: SymbolNode): Option[ujson.Value] =
    for {
      service <- node.service
      file    <- node.file
      line    <- node.line
      root    <- store.services.collectFirst { case (`service`, r) => Paths.get(r).toAbsolutePath.normalize }
      path     = root.resolve(file).normalize if path.startsWith(root) && Files.isRegularFile(path)
      lines   <- Try(Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector).toOption
    } yield {
      val end   = node.endLine.getOrElse(line + 15).max(line)
      val from  = (line - 3).max(1)
      val to    = (end + 1).min(lines.size).min(from + 400)
      ujson.Obj(
        "file"      -> file,
        "service"   -> service,
        "startLine" -> from,
        "focusLine" -> line,
        "endLine"   -> end,
        "lines"     -> ujson.Arr.from(lines.slice(from - 1, to).map(ujson.Str(_)))
      )
    }
}

object Api {
  final case class Response(status: Int, body: ujson.Value)
}

object Json {
  def node(s: SymbolNode): ujson.Value = ujson.Obj(
    "symbol"       -> s.symbol,
    "kind"         -> s.kind.id,
    "name"         -> s.name,
    "display"      -> s.display,
    "ownerDisplay" -> s.ownerDisplay,
    "owner"        -> s.owner,
    "signature"    -> s.signature,
    "service"      -> s.service.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
    "file"         -> s.file.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
    "line"         -> s.line.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
    "abstract"     -> s.isAbstract,
    "external"     -> s.isExternal
  )

  private def sites(sites: Seq[(String, Int)]): ujson.Value =
    ujson.Arr.from(sites.map { case (f, l) => ujson.Obj("file" -> f, "line" -> l) })

  def callee(c: Callee): ujson.Value = ujson.Obj(
    "target"     -> node(c.target),
    "sites"      -> sites(c.sites),
    "synthetic"  -> c.synthetic,
    "fork"       -> c.isFork,
    "candidates" -> ujson.Arr.from(c.candidates.map(node)),
    "sole"       -> c.soleImplementation.fold[ujson.Value](ujson.Null)(node)
  )

  def caller(c: Caller): ujson.Value = ujson.Obj(
    "caller" -> node(c.caller),
    "via"    -> node(c.via),
    "sites"  -> sites(c.sites)
  )

  def step(s: PathStep): ujson.Value = ujson.Obj(
    "node" -> node(s.node),
    "via"  -> s.via.fold[ujson.Value](ujson.Null)(node)
  )
}
