package dev.pylon.indexer.remote

import dev.pylon.indexer.endpoints.{ParsedFile, Tapir, Trees}
import scala.meta._

/**
 * Outbound HTTP calls: sttp (client3/client4), http4s client, Play WS, Akka/Pekko HTTP client, and
 * Tapir clients interpreting a shared endpoint value. The URL is evaluated statically
 * ([[UrlTemplate]]): the base URL becomes the hint, the rest a path template.
 */
object HttpClients {

  private val Verbs = Set("get", "post", "put", "patch", "delete", "head", "options")

  def scan(f: ParsedFile, vals: UrlTemplate.Vals, tapir: Tapir.Shapes): Seq[ClientSite] = {
    val out = Seq.newBuilder[ClientSite]

    def site(verb: String, url: Term, library: String, at: Tree): Unit = {
      val (path, hint) = UrlTemplate.toPath(UrlTemplate.eval(f, url, vals))
      out += ClientSite("http", verb, path, None, hint, library, f.uri, at.pos.startLine + 1, at.pos.start)
    }

    def symbolsOf(t: Term): Seq[String] = Trees.lastName(t).toSeq.flatMap(f.symbols)
    def from(t: Term, prefixes: String*): Boolean = symbolsOf(t).exists(s => prefixes.exists(s.startsWith))
    def named(args: List[Term], name: String): Option[Term] =
      args.collectFirst { case Term.Assign(n: Term.Name, v) if n.value == name => v }
    def positional(args: List[Term]): List[Term] = args.filterNot(_.isInstanceOf[Term.Assign])
    def verbOf(t: Term): Option[String] = Trees.lastName(t).map(_.value.toUpperCase).filter(v => Verbs(v.toLowerCase))

    // A Request built inline: Request[F](Method.POST, uri), Request(method = ..., uri = ...), POST(body, uri)
    def requestParts(t: Term): Option[(String, Term)] = t match {
      case a: Term.Apply =>
        val fun = a.fun match { case t: Term.ApplyType => t.fun; case other => other }
        val args = Trees.args(a)
        if (Trees.lastName(fun).exists(_.value == "Request") && from(fun, "org/http4s/Request")) {
          val verb = named(args, "method").orElse(positional(args).headOption).flatMap(verbOf).getOrElse("GET")
          named(args, "uri").orElse(positional(args).lift(1)).map(verb -> _)
        } else if (verbOf(fun).isDefined && from(fun, "org/http4s/")) {
          // client DSL: GET(uri), POST(body, uri)
          args.lastOption.map(verbOf(fun).get -> _)
        } else None
      case _ => None
    }

    f.tree.traverse {
      // sttp: basicRequest.get(uri"..."), quickRequest.post(uri), .method(Method.PUT, uri)
      case a: Term.Apply if (a.fun match {
            case s: Term.Select => (Verbs(s.name.value) || s.name.value == "method") && from(s, "sttp/client3/", "sttp/client4/")
            case _              => false
          }) =>
        val s = a.fun.asInstanceOf[Term.Select]
        Trees.args(a) match {
          case m :: url :: _ if s.name.value == "method" => site(verbOf(m).getOrElse("ANY"), url, "sttp", a)
          case url :: _ if s.name.value != "method"      => site(s.name.value.toUpperCase, url, "sttp", a)
          case _                                         =>
        }

      // Play WS: ws.url(x).get(), .post(body), .execute("PUT")
      case a: Term.Apply if (a.fun match {
            case s: Term.Select => (Verbs(s.name.value) || s.name.value == "execute") && from(s, "play/api/libs/ws/")
            case _              => false
          }) =>
        val s = a.fun.asInstanceOf[Term.Select]
        s.qual match {
          case u: Term.Apply if Trees.lastName(u.fun).exists(_.value == "url") && Trees.args(u).nonEmpty =>
            val verb =
              if (s.name.value == "execute") Trees.args(a).headOption.flatMap(Trees.stringLit).map(_.toUpperCase).getOrElse("GET")
              else s.name.value.toUpperCase
            site(verb, Trees.args(u).head, "play-ws", a)
          case _ =>
        }

      // http4s client: client.expect[A](uri | request), client.run(request), client.get(uri)(f), ...
      case a: Term.Apply if (a.fun match {
            case t: Term.ApplyType => from(t.fun, "org/http4s/client/Client#")
            case s: Term.Select    => from(s, "org/http4s/client/Client#")
            case _                 => false
          }) =>
        val method = Trees.lastName(a.fun).map(_.value).getOrElse("")
        Trees.args(a).headOption.foreach { target =>
          requestParts(target) match {
            case Some((verb, url)) => site(verb, url, "http4s-client", a)
            case None if Set("expect", "expectOption", "get", "fetchAs", "expectOr", "statusFromUri", "successful") (method) =>
              site("GET", target, "http4s-client", a)
            case None =>
          }
        }

      // Akka / Pekko HTTP: HttpRequest(HttpMethods.POST, uri = ...), Get(uri), Post(uri, entity)
      case a: Term.Apply if from(a.fun, "akka/http/scaladsl/model/HttpRequest", "org/apache/pekko/http/scaladsl/model/HttpRequest") =>
        val args = Trees.args(a)
        val verb = named(args, "method").orElse(positional(args).headOption.filter(t => verbOf(t).isDefined)).flatMap(verbOf)
        val url  = named(args, "uri").orElse(positional(args).find(t => verbOf(t).isEmpty))
        url.foreach(u => site(verb.getOrElse("GET"), u, akkaLibrary(symbolsOf(a.fun)), a))
      case a: Term.Apply if (verbOf(a.fun).isDefined && Trees.lastName(a.fun).exists(_.value.head.isUpper) &&
            from(a.fun, "akka/http/scaladsl/client/RequestBuilding", "org/apache/pekko/http/scaladsl/client/RequestBuilding")) =>
        Trees.args(a).headOption.foreach(u => site(verbOf(a.fun).get, u, akkaLibrary(symbolsOf(a.fun)), a))

      // Tapir clients: SttpClientInterpreter().toRequest(endpoint, baseUri), toClient, toSecureRequest, ...
      case a: Term.Apply if Trees.lastName(a.fun).exists(n => n.value.startsWith("to")) && from(a.fun, "sttp/tapir/client/") =>
        for {
          ep    <- Trees.args(a).headOption
          name  <- Trees.lastName(ep)
          sym   <- f.symbols(name).headOption
        } {
          val shape = tapir.valShape(sym, Set.empty)
          val hint  = Trees.args(a).lift(1).map(b => UrlTemplate.toPath(UrlTemplate.eval(f, b, vals))._2.getOrElse(b.syntax))
          out += ClientSite("http", shape.flatMap(_.verb).getOrElse("ANY"), shape.fold("{}")(_.path), Some(sym), hint,
            "tapir-client", f.uri, a.pos.startLine + 1, a.pos.start)
        }
    }

    out.result()
  }

  private def akkaLibrary(symbols: Seq[String]): String =
    if (symbols.exists(_.startsWith("org/apache/pekko/"))) "pekko-http-client" else "akka-http-client"
}
