package dev.pylon.indexer.endpoints

import scala.collection.mutable
import scala.meta._

/**
 * Akka HTTP and Pekko HTTP routing DSL.
 *
 * Directive trees are walked carrying the path prefix and HTTP method seen so far. A route is
 * emitted where the path is complete (`path`, `pathEnd`, ...) and a method is known, or as `ANY`
 * under a complete path that never sets one. A reference to another route-holding def/val inside a
 * directive context (`pathPrefix("admin") { adminRoutes }`) is an explicit mount.
 */
object AkkaHttp {

  private val Bases = Seq("akka/http/scaladsl/server/" -> "akka-http", "org/apache/pekko/http/scaladsl/server/" -> "pekko-http")

  private val PathDirectives  = Set("path", "pathPrefix", "rawPathPrefix", "pathPrefixTest", "pathSuffix")
  private val PathEnds        = Set("pathEnd", "pathEndOrSingleSlash", "pathSingleSlash", "redirectToTrailingSlashIfMissing")
  private val MethodNames     = Set("get", "post", "put", "patch", "delete", "head", "options")
  private val Concatenations  = Set("concat", "~")

  private final case class Ctx(prefix: Seq[String], complete: Boolean, verb: Option[String], emitted: Boolean)

  private sealed trait Effect
  private final case class PathEffect(segments: Seq[String], completes: Boolean, extractions: Int) extends Effect
  private final case class VerbEffect(verb: String) extends Effect
  private case object PassThrough extends Effect

  def scan(file: ParsedFile): Found = {
    val framework = Bases.collectFirst {
      case (base, fw) if file.doc.occurrences.exists(_.symbol.startsWith(base)) => (base, fw)
    }
    framework.fold(Found()) { case (base, fw) => new Walker(file, base, fw).run() }
  }

  private final class Walker(file: ParsedFile, base: String, framework: String) {
    private val routes  = mutable.ArrayBuffer.empty[Route]
    private val mounts  = mutable.ArrayBuffer.empty[Mount]
    private val visited = mutable.Set.empty[Tree]

    def run(): Found = {
      def scanTree(t: Tree): Unit =
        if (!visited(t)) t match {
          case a: Term.Apply if effects(a.fun).isDefined => visit(a, Ctx(Nil, complete = false, None, emitted = false))
          case _                                          => t.children.foreach(scanTree)
        }
      scanTree(file.tree)
      Found(routes.toSeq, mounts.toSeq)
    }

    private def directiveName(t: Tree): Option[String] = t match {
      case n: Term.Name if file.symbols(n).exists(_.startsWith(base)) => Some(n.value)
      case s: Term.Select                                             => directiveName(s.name)
      case _                                                          => None
    }

    /** The effects of a directive expression (`get`, `path("a" / Segment)`, `get & path("x")`), if it is one. */
    private def effects(fun: Term): Option[Seq[Effect]] = fun match {
      case i: Term.ApplyInfix if i.op.value == "&" =>
        for { l <- effects(i.lhs); r <- i.argClause.values.headOption.flatMap(effects) } yield l ++ r
      case a: Term.Apply =>
        directiveName(a.fun).map {
          case n if PathDirectives(n) =>
            val (segs, extractions) = Trees.args(a).headOption.map(matcher).getOrElse((Nil, 0))
            Seq(PathEffect(segs, completes = n == "path" || n == "pathSuffix", extractions))
          case n if PathEnds(n) => Seq(PathEffect(Nil, completes = true, 0))
          case "method" =>
            Seq(Trees.args(a).headOption.flatMap(Trees.lastName).map(m => VerbEffect(m.value.toUpperCase)).getOrElse(PassThrough))
          case n if Concatenations(n) => Nil
          case _                      => Seq(PassThrough)
        }.filterNot(_ => directiveName(a.fun).exists(Concatenations))
      case other =>
        directiveName(other).map {
          case n if MethodNames(n) => Seq(VerbEffect(n.toUpperCase))
          case n if PathEnds(n)    => Seq(PathEffect(Nil, completes = true, 0))
          case _                   => Seq(PassThrough)
        }
    }

    /** Path matcher -> segments and number of extracted values (`"items" / LongNumber` -> items/{long}, 1). */
    private def matcher(t: Term): (Seq[String], Int) = t match {
      case Lit.String(v) => (Trees.literalSegments(v), 0)
      case i: Term.ApplyInfix if i.op.value == "/" || i.op.value == "~" =>
        val (l, ln) = matcher(i.lhs)
        val (r, rn) = i.argClause.values.map(matcher).foldLeft((Seq.empty[String], 0)) { case ((s, n), (s2, n2)) => (s ++ s2, n + n2) }
        (l ++ r, ln + rn)
      case n: Term.Name =>
        n.value match {
          case "Segment"                         => (Seq("{segment}"), 1)
          case "IntNumber"                       => (Seq("{int}"), 1)
          case "LongNumber"                      => (Seq("{long}"), 1)
          case "JavaUUID"                        => (Seq("{uuid}"), 1)
          case "DoubleNumber"                    => (Seq("{double}"), 1)
          case "Remaining" | "RemainingPath" | "Segments" => (Seq("{*}"), 1)
          case "Slash" | "PathEnd" | "Neutral"   => (Nil, 0)
          case _                                 => (Seq("{?}"), 1)
        }
      case s: Term.Select => matcher(s.name)
      case _              => (Seq("{?}"), 1)
    }

    /** Names extracted values after the lambda parameters of the directive body (`{ id => ... }`). */
    private def nameExtractions(segs: Seq[String], extractions: Int, body: Option[Term]): Seq[String] = {
      val params = body.toSeq.flatMap {
        case f: Term.Function => f.paramClause.values.map(_.name.value)
        case b: Term.Block =>
          b.stats.headOption.toSeq.flatMap {
            case f: Term.Function => f.paramClause.values.map(_.name.value)
            case _                => Nil
          }
        case _ => Nil
      }
      if (extractions == 0 || params.size < extractions) segs
      else {
        val names = params.takeRight(extractions).iterator
        segs.map(s => if (s.startsWith("{") && s != "{*}" && s != "{?}" && names.hasNext) s"{${names.next()}}" else s)
      }
    }

    private def emit(verb: String, ctx: Ctx, at: Tree, scope: Tree): Unit =
      routes += Route(framework, verb, ctx.prefix, file.holderOf(at), file.uri, at.pos.startLine + 1, at.pos.endLine + 1,
        scope = Some(Trees.range(scope)))

    /** Visits a tree in a directive context; returns whether a route was emitted below. */
    private def visit(t: Tree, ctx: Ctx): Boolean = {
      visited += t
      t match {
        case a: Term.Apply if effects(a.fun).isDefined && Trees.args(a).nonEmpty && !directiveName(a.fun).exists(Concatenations) =>
          val body  = Trees.args(a).lastOption
          var next  = ctx
          effects(a.fun).get.foreach {
            case PathEffect(segs, completes, n) =>
              next = next.copy(prefix = next.prefix ++ nameExtractions(segs, n, body), complete = next.complete || completes)
            case VerbEffect(v) => next = next.copy(verb = Some(v))
            case PassThrough   =>
          }
          val routeHere = next.complete && next.verb.isDefined && !next.emitted && (next.verb != ctx.verb || next.complete != ctx.complete)
          if (routeHere) {
            emit(next.verb.get, next, a, body.get)
            body.foreach(visit(_, next.copy(emitted = true)))
            true
          } else {
            val below = body.exists(visit(_, next))
            if (!below && next.complete && !ctx.complete && next.verb.isEmpty && !next.emitted) { emit("ANY", next, a, body.get); true }
            else below
          }

        case a: Term.Apply if directiveName(a.fun).exists(Concatenations) =>
          Trees.args(a).map(visit(_, ctx)).foldLeft(false)(_ || _)

        case i: Term.ApplyInfix if i.op.value == "~" && directiveName(i.op).isDefined =>
          (visit(i.lhs, ctx) +: i.argClause.values.map(visit(_, ctx))).foldLeft(false)(_ || _)

        case n @ (_: Term.Name | _: Term.Select) if !ctx.emitted && (ctx.prefix.nonEmpty || ctx.verb.isDefined) =>
          // A reference to another route definition inside a directive: it is mounted here.
          for {
            container <- file.holderOf(n).toSeq
            mounted   <- Trees.lastName(n.asInstanceOf[Term]).toSeq.flatMap(file.symbols)
            if mounted != container
          } mounts += Mount(container, ctx.prefix, mounted, explicit = true)
          false

        case other => other.children.map(visit(_, ctx)).foldLeft(false)(_ || _)
      }
    }
  }
}
