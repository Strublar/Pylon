package dev.pylon.indexer

import dev.pylon.core.SymbolKind
import scala.meta.internal.semanticdb.Scala._

/** Helpers over SemanticDB symbol strings (`com/acme/ProviderA#search().`). */
object Syms {

  def isGlobalMethod(sym: String): Boolean = sym.isGlobal && sym.desc.isMethod

  def isConstructor(sym: String): Boolean = isGlobalMethod(sym) && sym.desc.value == "<init>"

  /** Primary constructor symbol of a class or trait symbol (`a/B#` -> ``a/B#`<init>`().``). */
  def primaryConstructor(typeSym: String): String = s"$typeSym`<init>`()."

  def name(sym: String): String = sym.desc.value

  /** Type or object names from the outermost non-package owner down to `sym`, e.g. `Outer.Inner`. */
  def typeDisplay(sym: String): String = {
    val chain = sym.ownerChain.filterNot(s => s.isPackage || s.isRootPackage || s.isEmptyPackage)
    val names = chain.map(s => cleanName(s.desc.value)).filter(_.nonEmpty)
    if (names.nonEmpty) names.mkString(".")
    else sym.ownerChain.filter(_.isPackage).lastOption.map(_.desc.value).getOrElse(sym.desc.value)
  }

  /** Human name: `ProviderA.search`, `new ProviderA`, `ProviderA`. */
  def display(sym: String): String =
    if (sym.isType || isTopLevelObject(sym)) typeDisplay(sym)
    else if (isConstructor(sym)) s"new ${typeDisplay(sym.owner)}"
    else {
      val owner = typeDisplay(sym.owner)
      if (owner.isEmpty) name(sym) else s"$owner.${name(sym)}"
    }

  /** Kind of a symbol known only by name (external references). */
  def guessKind(sym: String): SymbolKind =
    if (isConstructor(sym)) SymbolKind.Constructor
    else if (sym.desc.isMethod) SymbolKind.Method
    else if (sym.isType) SymbolKind.Class
    else if (sym.isTerm) SymbolKind.Value
    else SymbolKind.Other

  /** Scala 3 top-level definitions live in a synthetic `File$package` object; show the package instead. */
  private def cleanName(n: String): String = if (n.endsWith("$package")) "" else n

  // A term directly in a package is an object (`a/Main.`); other terms are vals and nested objects,
  // which read fine as `Owner.name`.
  private def isTopLevelObject(sym: String): Boolean = sym.isTerm && !sym.desc.isMethod && sym.owner.isPackage
}
