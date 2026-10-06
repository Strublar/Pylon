package dev.pylon.indexer.endpoints

import scala.meta._

/**
 * http4s DSL routes: `case GET -> Root / "items" / LongVar(id) :? Q(q) => body` (also `req @ ...`),
 * with `Router("/api" -> routes)` as explicit mounts.
 */
object Http4s {

  val Framework = "http4s"
  private val Pkg = Seq("org/http4s/")

  def scan(file: ParsedFile): Found = {
    val routes = file.tree.collect {
      case c: Case =>
        routeOf(file, c.pat).map { case (verb, segments) =>
          Route(
            Framework,
            verb,
            segments,
            file.holderOf(c),
            file.uri,
            c.pos.startLine + 1,
            c.pos.endLine + 1,
            scope = Some(Trees.range(c.body))
          )
        }
    }.flatten

    val mounts = file.tree.collect {
      case a: Term.Apply if isRouter(file, a.fun) =>
        val container = file.holderOf(a)
        Trees.args(a).flatMap {
          case arrow: Term.ApplyInfix if arrow.op.value == "->" =>
            val prefix = Trees.stringLit(arrow.lhs).map(Trees.literalSegments).getOrElse(Seq("{?}"))
            for {
              c       <- container.toSeq
              mounted <- arrow.argClause.values.flatMap(Trees.referencedTerms(file, _))
            } yield Mount(c, prefix, mounted, explicit = true)
          case _ => Nil
        }
    }.flatten

    Found(routes, mounts)
  }

  private def isRouter(file: ParsedFile, fun: Term): Boolean =
    Trees.lastName(fun).exists { n =>
      (n.value == "Router" || n.value == "apply") && file.symbols(n).exists(_.startsWith("org/http4s/server/Router."))
    }

  /** `VERB -> path` patterns whose `->` is http4s's. */
  private def routeOf(file: ParsedFile, pat: Pat): Option[(String, Seq[String])] = pat match {
    case b: Pat.Bind => routeOf(file, b.rhs)
    // `:?` and `+&` bind more loosely than `->`: `GET -> Root / "x" :? Q(q)` is `(GET -> Root / "x") :? Q(q)`.
    case p: Pat.ExtractInfix if p.op.value == ":?" || p.op.value == "+&" => routeOf(file, p.lhs)
    case p: Pat.ExtractInfix if (p.op.value == "->" || p.op.value == "->>") && file.resolvesInto(p.op, Pkg) =>
      val verb = p.lhs match {
        case n: Term.Name if file.resolvesInto(n, Seq("org/http4s/")) => n.value.toUpperCase
        case _                                                         => "ANY"
      }
      p.argClause.values.headOption.map(path => (verb, segments(path)))
    case _ => None
  }

  private def segments(p: Pat): Seq[String] = p match {
    case i: Pat.ExtractInfix if i.op.value == "/"  => segments(i.lhs) ++ i.argClause.values.flatMap(segment)
    case i: Pat.ExtractInfix if i.op.value == ":?" => segments(i.lhs)
    case i: Pat.ExtractInfix if i.op.value == "+&" => segments(i.lhs)
    case n: Term.Name if n.value == "Root"         => Nil
    case other                                     => segment(other)
  }

  private def segment(p: Pat): Seq[String] = p match {
    case Lit.String(v)                       => Trees.literalSegments(v)
    case v: Pat.Var                          => Seq(s"{${v.name.value}}")
    case e: Pat.Extract =>
      e.argClause.values.collectFirst { case v: Pat.Var => s"{${v.name.value}}" }.toSeq match {
        case Nil => Seq(s"{${e.fun.syntax}}")
        case s   => s
      }
    case b: Pat.Bind                         => segment(b.rhs)
    case _: Pat.Wildcard                     => Seq("{_}")
    case i: Pat.ExtractInfix                 => segments(i)
    case _                                   => Seq("{?}")
  }
}

/**
 * ZIO HTTP routes. 3.x: `Method.GET / "items" / long("id") -> handler {...}`;
 * 2.x: `Http.collect { case Method.GET -> !! / "items" => ... }`.
 */
object ZioHttp {

  val Framework = "zio-http"
  private val Pkg = Seq("zio/http/")

  def scan(file: ParsedFile): Found = {
    val v3 = file.tree.collect {
      case a: Term.ApplyInfix if a.op.value == "->" && file.resolvesInto(a.op, Pkg) =>
        pattern(file, a.lhs).map { case (verb, segs) =>
          val handler = a.argClause.values
          val scope   = if (handler.isEmpty) None else Some((handler.head.pos.start, handler.last.pos.end))
          Route(Framework, verb, segs, file.holderOf(a), file.uri, a.pos.startLine + 1, a.pos.endLine + 1, scope = scope)
        }
    }.flatten

    val v2 = file.tree.collect {
      case c: Case =>
        c.pat match {
          case p: Pat.ExtractInfix if p.op.value == "->" =>
            for {
              verb <- verbOf(file, p.lhs)
              path <- p.argClause.values.headOption
            } yield Route(Framework, verb, patSegments(path), file.holderOf(c), file.uri, c.pos.startLine + 1, c.pos.endLine + 1,
              scope = Some(Trees.range(c.body)))
          case _ => None
        }
    }.flatten

    Found(v3 ++ v2)
  }

  private def verbOf(file: ParsedFile, t: Tree): Option[String] = t match {
    case s: Term.Select if file.resolvesInto(s.name, Seq("zio/http/Method.")) => Some(s.name.value.toUpperCase)
    case n: Term.Name if file.resolvesInto(n, Seq("zio/http/Method."))      => Some(n.value.toUpperCase)
    case _                                                                  => None
  }

  /** A 3.x route pattern: the method, then `/`-separated segments and path codecs. */
  private def pattern(file: ParsedFile, t: Term): Option[(String, Seq[String])] = t match {
    case i: Term.ApplyInfix if i.op.value == "/" =>
      pattern(file, i.lhs).map { case (v, segs) => (v, segs ++ i.argClause.values.flatMap(segment)) }
    case a: Term.Apply if Trees.lastName(a.fun).exists(_.value == "RoutePattern") =>
      Trees.args(a) match {
        case m :: p :: _ => verbOf(file, m).map(v => (v, Trees.stringLit(p).map(Trees.literalSegments).getOrElse(Seq("{?}"))))
        case _           => None
      }
    case other => verbOf(file, other).map(v => (v, Nil))
  }

  private def segment(t: Term): Seq[String] = t match {
    case Lit.String(v) => Trees.literalSegments(v)
    case a: Term.Apply =>
      Trees.args(a).headOption.flatMap(Trees.stringLit).map(n => s"{$n}").toSeq match {
        case Nil => Seq("{?}")
        case s   => s
      }
    case n: Term.Name if n.value == "trailing" => Seq("{*}")
    case _                                     => Seq("{?}")
  }

  private def patSegments(p: Pat): Seq[String] = p match {
    case i: Pat.ExtractInfix if i.op.value == "/" => patSegments(i.lhs) ++ i.argClause.values.flatMap(patSegments)
    case n: Term.Name if n.value == "!!" || n.value == "Root" => Nil
    case Lit.String(v)                            => Trees.literalSegments(v)
    case v: Pat.Var                               => Seq(s"{${v.name.value}}")
    case e: Pat.Extract                           => e.argClause.values.collectFirst { case v: Pat.Var => s"{${v.name.value}}" }.toSeq
    case _                                        => Seq("{?}")
  }
}
