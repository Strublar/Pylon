package dev.pylon.core

/** Kind of a symbol node. Mirrors the SemanticDB kinds Pylon cares about. */
sealed abstract class SymbolKind(val id: String)
object SymbolKind {
  case object Trait       extends SymbolKind("trait")
  case object Class       extends SymbolKind("class")
  case object Object      extends SymbolKind("object")
  case object Method      extends SymbolKind("method")
  case object Constructor extends SymbolKind("constructor")
  case object Value       extends SymbolKind("val")
  /** An HTTP route (Phase 3): `display` is `VERB /path`, `signature` the framework. */
  case object Endpoint    extends SymbolKind("endpoint")
  case object Other       extends SymbolKind("other")

  val all: Seq[SymbolKind] = Seq(Trait, Class, Object, Method, Constructor, Value, Endpoint, Other)
  def fromId(id: String): SymbolKind = all.find(_.id == id).getOrElse(Other)
}

/**
 * A node of the graph: a type or a member.
 *
 * @param symbol    SemanticDB global symbol, e.g. `com/acme/ProviderA#search().`
 * @param name      short name, e.g. `search`
 * @param owner     symbol of the enclosing type (empty for packages)
 * @param display   human name, e.g. `ProviderA.search`
 * @param signature rendered signature, e.g. `(query: Query): List[Hit]`
 * @param service   service that defines it, `None` for external (library) symbols
 * @param isAbstract true for abstract methods and for traits
 * @param endLine   last line of the definition, when known (methods, vals)
 */
final case class SymbolNode(
    symbol: String,
    kind: SymbolKind,
    name: String,
    owner: String,
    display: String,
    signature: String,
    service: Option[String],
    file: Option[String],
    line: Option[Int],
    isAbstract: Boolean,
    endLine: Option[Int] = None
) {
  def isExternal: Boolean = service.isEmpty

  /** `ProviderA` for `ProviderA.search`, `ProviderA` for `new ProviderA`. */
  def ownerDisplay: String =
    if (kind == SymbolKind.Constructor) display.stripPrefix("new ")
    else if (display.endsWith(s".$name")) display.dropRight(name.length + 1)
    else if (kind == SymbolKind.Method || kind == SymbolKind.Value) ""
    else display
}

/** `caller` calls `callee` at `file:line`. `synthetic` marks compiler-inserted calls (implicits, for-comprehensions). */
final case class CallEdge(
    caller: String,
    callee: String,
    file: String,
    line: Int,
    synthetic: Boolean
)

/** Everything extracted from one service. */
final case class ServiceGraph(
    service: String,
    root: String,
    symbols: Seq[SymbolNode],
    externals: Seq[SymbolNode],
    extendsEdges: Seq[(String, String)],
    overrides: Seq[(String, String)],
    calls: Seq[CallEdge]
)

/** A callee of a method, grouped over all its call sites. */
final case class Callee(
    target: SymbolNode,
    sites: Seq[(String, Int)],
    synthetic: Boolean,
    candidates: Seq[SymbolNode]
) {

  /** True when the runtime target must be chosen among several implementations. */
  def isFork: Boolean = candidates.size > 1

  /** The only possible implementation of an abstract target, when there is exactly one. */
  def soleImplementation: Option[SymbolNode] =
    if (target.isAbstract && candidates.size == 1) candidates.headOption else None
}

/** A caller of a method. `via` is the method actually referenced at the call site (the method itself or one it overrides). */
final case class Caller(
    caller: SymbolNode,
    via: SymbolNode,
    sites: Seq[(String, Int)]
)

/** One step of a path towards an entrypoint: `node` is reached by calling `via`. */
final case class PathStep(node: SymbolNode, via: Option[SymbolNode])
