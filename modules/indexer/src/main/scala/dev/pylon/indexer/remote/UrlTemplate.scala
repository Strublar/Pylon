package dev.pylon.indexer.remote

import dev.pylon.indexer.endpoints.ParsedFile
import scala.meta._

/**
 * Static evaluation of URL and topic expressions: literals, `s"…"`/`uri"…"` interpolation, `+`
 * concatenation, http4s `uri / "segment"`, and vals (followed across files). Whatever cannot be
 * evaluated becomes a hole carrying its source text.
 */
object UrlTemplate {

  sealed trait Piece
  final case class Text(value: String) extends Piece
  final case class Hole(source: String) extends Piece

  /** Right-hand sides of vals by symbol, to follow `catalogUrl` to `config.getString("catalog.url")`. */
  final class Vals(files: Seq[ParsedFile]) {
    private val rhs: Map[String, (ParsedFile, Term)] = files.flatMap { f =>
      f.tree.collect {
        case d: Defn.Val =>
          d.pats.headOption.collect { case v: Pat.Var => v.name }.flatMap(n => f.symbols(n).headOption).map(_ -> (f, d.rhs))
      }.flatten
    }.toMap
    def get(sym: String): Option[(ParsedFile, Term)] = rhs.get(sym)
  }

  def eval(f: ParsedFile, t: Term, vals: Vals, depth: Int = 0): Seq[Piece] =
    if (depth > 8) Seq(Hole(t.syntax))
    else
      t match {
        case Lit.String(v) => Seq(Text(v))
        case b: Term.Block if b.stats.size == 1 && b.stats.head.isInstanceOf[Term] => // `${x}` in interpolations
          eval(f, b.stats.head.asInstanceOf[Term], vals, depth + 1)
        case i: Term.Interpolate =>
          val parts = i.parts.map { case Lit.String(v) => v; case other => other.syntax }
          val args  = i.args.map(a => eval(f, a, vals, depth + 1))
          parts.zipAll(args, "", Nil).flatMap { case (p, a) => (if (p.isEmpty) Nil else Seq(Text(p))) ++ a }
        case i: Term.ApplyInfix if i.op.value == "+" =>
          eval(f, i.lhs, vals, depth + 1) ++ i.argClause.values.flatMap(eval(f, _, vals, depth + 1))
        case i: Term.ApplyInfix if i.op.value == "/" && f.symbols(i.op).exists(_.startsWith("org/http4s/")) =>
          eval(f, i.lhs, vals, depth + 1) ++ (Text("/") +: i.argClause.values.flatMap(eval(f, _, vals, depth + 1)))
        case a: Term.Apply if isUriWrapper(a) && a.argClause.values.size == 1 =>
          eval(f, a.argClause.values.head, vals, depth + 1)
        case ref @ (_: Term.Name | _: Term.Select) =>
          val name = ref match { case s: Term.Select => s.name; case n => n }
          f.symbols(name).flatMap(vals.get).headOption match {
            case Some((vf, rhs)) =>
              eval(vf, rhs, vals, depth + 1) match {
                case Seq(Hole(_)) => Seq(Hole(rhs.syntax)) // keep the val's definition as the hint
                case pieces       => pieces
              }
            case None => Seq(Hole(t.syntax))
          }
        case other => Seq(Hole(other.syntax))
      }

  /** `Uri.unsafeFromString(x)`, `Uri.parse(x)`, `uri(x)`, `url(x)`: the argument is the URL. */
  private def isUriWrapper(a: Term.Apply): Boolean = a.fun match {
    case s: Term.Select => Set("unsafeFromString", "unsafeParse", "parse", "fromString", "apply", "create")(s.name.value) &&
        Set("Uri", "URI", "Url", "URL").exists(n => s.qual.syntax.endsWith(n))
    case n: Term.Name   => Set("uri", "url", "Uri", "Some")(n.value)
    case _              => false
  }

  /** A path template and a hint: `Hole(base) + "/catalog/items/" + Hole(id)` -> (`/catalog/items/{}`, base). */
  def toPath(pieces: Seq[Piece]): (String, Option[String]) = {
    val (base, rest) = pieces match {
      case Hole(src) +: tail => (Some(src), tail)
      case all               => (None, all)
    }
    val rendered = rest.map { case Text(v) => v; case Hole(_) => "{}" }.mkString
    val (hostHint, path) = splitHost(rendered)
    val clean = ("/" + path.takeWhile(c => c != '?' && c != '#')).replaceAll("/{2,}", "/")
    val trimmed = if (clean.length > 1) clean.stripSuffix("/") else clean
    (trimmed, base.orElse(hostHint))
  }

  /** `http://catalog:8080/a/b` -> (Some(`http://catalog:8080`), `a/b`). */
  private def splitHost(s: String): (Option[String], String) = {
    val schemeEnd = s.indexOf("://")
    if (schemeEnd < 0) (None, s.stripPrefix("/"))
    else {
      val pathStart = s.indexOf('/', schemeEnd + 3)
      if (pathStart < 0) (Some(s), "") else (Some(s.substring(0, pathStart)), s.substring(pathStart + 1))
    }
  }

  private val Collections = Set("List", "Seq", "Set", "Vector", "Array", "of", "asList", "singletonList", "singleton", "apply", "topics", "NonEmptyList", "one")

  /** Every string a topic expression can evaluate to (`List("a", "b")`, `java.util.List.of("a")`, a val): Left = known, Right = unknown (source text). */
  def strings(f: ParsedFile, t: Term, vals: Vals): Seq[Either[String, String]] = t match {
    case a: Term.Apply if dev.pylon.indexer.endpoints.Trees.lastName(a.fun).exists(n => Collections(n.value)) =>
      a.argClause.values.flatMap(strings(f, _, vals))
    case s: Term.Select if s.name.value == "asJava" => strings(f, s.qual, vals)
    case other =>
      eval(f, other, vals) match {
        case pieces if pieces.forall(_.isInstanceOf[Text]) => Seq(Left(pieces.collect { case Text(v) => v }.mkString))
        case pieces => Seq(Right(pieces.map { case Text(v) => v; case Hole(h) => h }.mkString))
      }
  }
}
