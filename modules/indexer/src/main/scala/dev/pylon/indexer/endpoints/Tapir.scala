package dev.pylon.indexer.endpoints

import scala.collection.mutable
import scala.meta._

/**
 * Tapir endpoints. Endpoint values (`val search = base.get.in("search" / path[Long]("id"))`) are
 * evaluated statically, following references to other endpoint or input vals across files. An
 * endpoint becomes a route where server logic is attached (`search.serverLogic...(f)`); calls made in
 * that expression belong to it. The endpoint val is kept as the route key, for client-side linking.
 */
object Tapir {

  val Framework = "tapir"
  private val Pkg = "sttp/tapir/"

  private final case class Shape(verb: Option[String], segments: Seq[String])

  private val LogicPrefixes = Seq("serverLogic", "zServerLogic", "serverSecurityLogic", "zServerSecurityLogic", "handle")

  def scan(files: Seq[ParsedFile]): Found = {
    // Right-hand sides of every val, by symbol, to follow `base.get...` and `val idPath = path[Long]("id")`.
    val vals: Map[String, (ParsedFile, Term)] = files.flatMap { f =>
      f.tree.collect {
        case d: Defn.Val =>
          d.pats.headOption.collect { case v: Pat.Var => v.name }.flatMap(n => f.symbols(n).headOption).map(_ -> (f, d.rhs))
      }.flatten
    }.toMap

    val shapes = mutable.Map.empty[String, Option[Shape]]

    def valShape(sym: String, visiting: Set[String]): Option[Shape] =
      if (visiting(sym)) None
      else shapes.getOrElseUpdate(sym, vals.get(sym).flatMap { case (f, rhs) => shape(f, rhs, visiting + sym) })

    def shape(f: ParsedFile, t: Term, visiting: Set[String]): Option[Shape] = t match {
      case n: Term.Name =>
        val syms = f.symbols(n)
        if (syms.exists(s => s == s"${Pkg}Tapir#endpoint." || s == s"${Pkg}Tapir#infallibleEndpoint.")) Some(Shape(None, Nil))
        else syms.collectFirst(Function.unlift(valShape(_, visiting)))
      case s: Term.Select if f.resolvesInto(s.name, Seq(Pkg)) =>
        shape(f, s.qual, visiting).map(sh => withVerb(sh, s.name.value))
      case s: Term.Select => shape(f, s.name, visiting)
      case a: Term.Apply =>
        a.fun match {
          case s: Term.Select if f.resolvesInto(s.name, Seq(Pkg)) =>
            shape(f, s.qual, visiting).map { sh =>
              s.name.value match {
                case "in" | "securityIn" | "prependIn" =>
                  val segs = Trees.args(a).flatMap(inputSegments(f, _, visiting))
                  if (s.name.value == "prependIn") sh.copy(segments = segs ++ sh.segments) else sh.copy(segments = sh.segments ++ segs)
                case "method" => Trees.args(a).headOption.flatMap(Trees.lastName).fold(sh)(m => sh.copy(verb = Some(m.value.toUpperCase)))
                case _        => sh
              }
            }
          case at: Term.ApplyType => shape(f, at.fun, visiting)
          case _                  => None
        }
      case at: Term.ApplyType => shape(f, at.fun, visiting)
      case _                  => None
    }

    def withVerb(sh: Shape, name: String): Shape = name match {
      case "get" | "post" | "put" | "patch" | "delete" | "head" | "options" => sh.copy(verb = Some(name.toUpperCase))
      case _                                                               => sh
    }

    def inputSegments(f: ParsedFile, t: Term, visiting: Set[String]): Seq[String] = t match {
      case Lit.String(v) => Trees.literalSegments(v)
      case i: Term.ApplyInfix if i.op.value == "/" || i.op.value == "and" =>
        inputSegments(f, i.lhs, visiting) ++ i.argClause.values.flatMap(inputSegments(f, _, visiting))
      case a: Term.Apply =>
        Trees.lastName(a.fun).map(_.value) match {
          case Some("path")  => Trees.args(a).headOption.flatMap(Trees.stringLit).map(n => Seq(s"{$n}")).getOrElse(Seq("{param}"))
          case Some("paths") => Seq("{*}")
          case _             => Nil // query, header, body, ...
        }
      case at: Term.ApplyType if Trees.lastName(at.fun).exists(_.value == "path") => Seq("{param}")
      case n: Term.Name if n.value == "paths"                                    => Seq("{*}")
      case n: Term.Name =>
        // A val holding an input (`val idPath = path[Long]("id")`).
        f.symbols(n).flatMap(vals.get).headOption.toSeq.flatMap { case (vf, rhs) => inputSegments(vf, rhs, visiting) }
      case _ => Nil
    }

    /** The endpoint a server-logic call is attached to, descending through chained logic calls. */
    def endpointOf(f: ParsedFile, qual: Term): Option[(Option[String], Shape)] = qual match {
      case a: Term.Apply =>
        logicCall(f, a) match {
          case Some((inner, _)) => endpointOf(f, inner)
          case None             => shape(f, a, Set.empty).map(None -> _)
        }
      case at: Term.ApplyType => endpointOf(f, at.fun)
      case other =>
        val sym = Trees.lastName(other).flatMap(n => f.symbols(n).find(vals.contains))
        sym.flatMap(s => valShape(s, Set.empty).map(Some(s) -> _)).orElse(shape(f, other, Set.empty).map(None -> _))
    }

    /** `qual.serverLogicX[F](...)` -> (qual, method name). */
    def logicCall(f: ParsedFile, a: Term.Apply): Option[(Term, String)] = {
      val fun = a.fun match {
        case at: Term.ApplyType => at.fun
        case other              => other
      }
      fun match {
        case s: Term.Select if LogicPrefixes.exists(s.name.value.startsWith) && f.resolvesInto(s.name, Seq(Pkg)) =>
          Some(s.qual -> s.name.value)
        case _ => None
      }
    }

    val routes = files.flatMap { f =>
      val out     = mutable.ArrayBuffer.empty[Route]
      val handled = mutable.Set.empty[Tree]
      f.tree.traverse {
        case a: Term.Apply if !handled(a) && logicCall(f, a).isDefined =>
          val (qual, _) = logicCall(f, a).get
          endpointOf(f, qual).foreach { case (key, sh) =>
            out += Route(Framework, sh.verb.getOrElse("ANY"), sh.segments, f.holderOf(a), f.uri, a.pos.startLine + 1,
              a.pos.endLine + 1, scope = Some(Trees.range(a)), key = key)
            a.collect { case inner: Term.Apply => inner }.foreach(handled += _)
          }
      }
      out.toSeq
    }
    Found(routes)
  }
}
