package dev.pylon.indexer.endpoints

import scala.collection.mutable
import scala.meta._
import scala.meta.internal.semanticdb.{SymbolOccurrence, TextDocument}
import scala.meta.internal.semanticdb.Scala._

/**
 * A route found by an adapter, with a path relative to wherever its holder is mounted.
 *
 * @param holder  global def/val enclosing the route definition (or a pseudo-holder such as a Play
 *                routes file); mounts of the holder prefix the path
 * @param scope   source range (offsets in `file`) of the handler: calls made there belong to the endpoint
 * @param targets methods the endpoint calls directly (Play routes name their controller action)
 * @param key     framework-level identity of the endpoint definition (the Tapir endpoint val), for later linking
 */
final case class Route(
    framework: String,
    verb: String,
    segments: Seq[String],
    holder: Option[String],
    file: String,
    line: Int,
    endLine: Int,
    scope: Option[(Int, Int)] = None,
    targets: Seq[String] = Nil,
    key: Option[String] = None
)

/**
 * Routes held by `mounted` are reachable under `prefix` from `container`.
 * Explicit mounts come from the framework (`Router("/api" -> r)`, `pathPrefix("api") { r }`, Play `->`);
 * implicit ones are plain references (`a.routes <+> b.routes`) and only count when a holder has no explicit mount.
 */
final case class Mount(container: String, prefix: Seq[String], mounted: String, explicit: Boolean)

/** What an adapter found in a service. */
final case class Found(routes: Seq[Route] = Nil, mounts: Seq[Mount] = Nil) {
  def ++(o: Found): Found = Found(routes ++ o.routes, mounts ++ o.mounts)
}

/** A source file parsed once, with SemanticDB lookups by offset. */
final class ParsedFile(val uri: String, val text: String, val tree: Source, val doc: TextDocument) {
  private val lineStarts: Array[Int] = {
    val starts = mutable.ArrayBuffer(0)
    var i      = 0
    while (i < text.length) { if (text.charAt(i) == '\n') starts += i + 1; i += 1 }
    starts.toArray
  }

  def offset(r: scala.meta.internal.semanticdb.Range): Int =
    lineStarts(math.min(r.startLine, lineStarts.length - 1)) + r.startCharacter

  /** 1-based line of an offset. */
  def lineOf(offset: Int): Int = {
    val i = java.util.Arrays.binarySearch(lineStarts, offset)
    (if (i >= 0) i else -i - 2) + 1
  }

  private val byOffset: Map[Int, Seq[SymbolOccurrence]] =
    doc.occurrences.filter(_.range.isDefined).groupBy(o => offset(o.range.get))

  /** Symbols of the occurrences starting at `tree`'s position (an identifier may resolve to several, e.g. `IO` term and type). */
  def symbols(tree: Tree): Seq[String] = byOffset.getOrElse(tree.pos.start, Nil).map(_.symbol)

  def symbol(tree: Tree): Option[String] = symbols(tree).headOption

  def resolvesInto(tree: Tree, prefixes: Seq[String]): Boolean =
    symbols(tree).exists(s => prefixes.exists(s.startsWith))

  /** Global definitions (def/val/var/object/class) enclosing a tree, innermost first. */
  def enclosingDefinitions(tree: Tree): Seq[String] =
    Iterator
      .iterate(tree.parent)(_.flatMap(_.parent))
      .takeWhile(_.isDefined)
      .flatten
      .flatMap {
        case d: Defn.Def    => definitionOf(d.name)
        case d: Defn.Val    => d.pats.headOption.collect { case v: Pat.Var => v.name }.flatMap(definitionOf)
        case d: Defn.Var    => d.pats.headOption.collect { case v: Pat.Var => v.name }.flatMap(definitionOf)
        case d: Defn.Object => definitionOf(d.name)
        case d: Defn.Class  => definitionOf(d.name).map(c => s"$c`<init>`().")
        case _              => None
      }
      .toSeq

  def holderOf(tree: Tree): Option[String] = enclosingDefinitions(tree).headOption

  private def definitionOf(name: Tree): Option[String] =
    byOffset.getOrElse(name.pos.start, Nil).find(o => o.role.isDefinition && o.symbol.isGlobal).map(_.symbol)
}

/** Shared tree helpers for adapters. */
object Trees {

  def args(a: Term.Apply): List[Term] = a.argClause.values

  /** The name at the end of a reference: `a.b.c` -> `c`. */
  def lastName(t: Term): Option[Term.Name] = t match {
    case n: Term.Name    => Some(n)
    case s: Term.Select  => Some(s.name)
    case a: Term.ApplyType => lastName(a.fun)
    case _               => None
  }

  def stringLit(t: Tree): Option[String] = t match {
    case Lit.String(v) => Some(v)
    case _             => None
  }

  /** Splits a literal path piece (`"api/v1"`, `"/x"`) into segments. */
  def literalSegments(s: String): Seq[String] = s.split('/').toSeq.filter(_.nonEmpty)

  /** Every name inside `t` that resolves to a global term (a candidate route holder for mounts). */
  def referencedTerms(file: ParsedFile, t: Tree): Seq[String] =
    t.collect { case n: Term.Name => file.symbols(n) }.flatten.filter(s => s.isGlobal && s.isTerm).distinct

  def range(t: Tree): (Int, Int) = (t.pos.start, t.pos.end)
}
