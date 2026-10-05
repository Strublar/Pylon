package dev.pylon.indexer

import dev.pylon.core._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.meta._
import scala.meta.internal.semanticdb.{Scope => SScope, Synthetic => SSynthetic, Tree => STree, Type => SType, _}
import scala.meta.internal.semanticdb.Scala._
import scala.util.control.NonFatal

/**
 * Turns the SemanticDB documents of one service into a [[ServiceGraph]].
 *
 * SemanticDB gives resolved symbols for every reference plus `overriddenSymbols` and class
 * parents; scalameta syntax trees give the extent of each definition so that a reference
 * can be attributed to the method whose body contains it.
 */
object Extractor {

  def extract(service: String, root: Path, docs: Seq[LoadedDocument], warn: String => Unit = Console.err.println): ServiceGraph = {
    val symtab: Map[String, SymbolInformation] =
      docs.iterator.flatMap(_.doc.symbols).filter(_.symbol.isGlobal).map(i => i.symbol -> i).toMap

    val defined     = mutable.LinkedHashMap.empty[String, SymbolNode]
    val extendsE    = mutable.LinkedHashSet.empty[(String, String)]
    val overridesE  = mutable.LinkedHashSet.empty[(String, String)]
    val calls       = mutable.ArrayBuffer.empty[CallEdge]

    docs.foreach { loaded =>
      val doc  = loaded.doc
      val path = root.resolve(doc.uri)
      val text = if (doc.text.nonEmpty) doc.text else new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      val lineStarts = computeLineStarts(text)
      def offset(r: scala.meta.internal.semanticdb.Range): Int =
        lineStarts(math.min(r.startLine, lineStarts.length - 1)) + r.startCharacter

      val definitionAt: Map[Int, String] = doc.occurrences.iterator
        .filter(o => o.role.isDefinition && o.symbol.isGlobal && o.range.isDefined)
        .map(o => offset(o.range.get) -> o.symbol)
        .toMap
      val definitionLine: Map[String, Int] = doc.occurrences.iterator
        .filter(o => o.role.isDefinition && o.symbol.isGlobal && o.range.isDefined)
        .map(o => o.symbol -> (o.range.get.startLine + 1))
        .toMap

      val parsed = parse(doc.uri, text, loaded.isScala3)
      if (parsed.isEmpty) warn(s"[pylon] could not parse ${doc.uri}; calls in this file are attributed to nothing")
      val scopes        = parsed.map(collectScopes(_, definitionAt)).getOrElse(Seq.empty)
      val declaredOnly  = scopes.filter(_.isDeclaration).map(_.symbol).toSet

      // Nodes for definitions in this file.
      doc.symbols.filter(_.symbol.isGlobal).foreach { info =>
        nodeKind(info).foreach { kind =>
          val abstractish = info.isAbstract || declaredOnly(info.symbol) || kind == SymbolKind.Trait
          defined(info.symbol) = SymbolNode(
            symbol = info.symbol,
            kind = kind,
            name = if (kind == SymbolKind.Constructor) "<init>" else Syms.name(info.symbol),
            owner = info.symbol.owner,
            display = Syms.display(info.symbol),
            signature = Signatures.render(info, symtab),
            service = Some(service),
            file = Some(doc.uri),
            line = definitionLine.get(info.symbol).orElse(definitionLine.get(info.symbol.owner)),
            isAbstract = abstractish && kind != SymbolKind.Object
          )
        }
        info.overriddenSymbols.filter(_.isGlobal).foreach(o => overridesE += info.symbol -> o)
        info.signature match {
          case cs: ClassSignature =>
            cs.parents.foreach {
              case TypeRef(_, parent, _) if parent.isGlobal && !ignoredParents(parent) => extendsE += info.symbol -> parent
              case _                                                                  =>
            }
          case _ =>
        }
      }

      // Initializer scopes (class bodies) are owned by the primary constructor; make sure it exists as a node.
      scopes.filter(_.isInitializer).foreach { s =>
        if (!defined.contains(s.symbol) && Syms.isConstructor(s.symbol))
          defined(s.symbol) = SymbolNode(
            symbol = s.symbol,
            kind = SymbolKind.Constructor,
            name = "<init>",
            owner = s.symbol.owner,
            display = Syms.display(s.symbol),
            signature = "",
            service = Some(service),
            file = Some(doc.uri),
            line = Some(s.line),
            isAbstract = false
          )
      }

      def enclosing(off: Int): Option[Scope] =
        scopes.filter(s => s.start <= off && off < s.end).sortBy(s => (s.end - s.start, -s.start)).headOption

      def record(sym: String, off: Int, line: Int, synthetic: Boolean): Unit =
        if (Syms.isGlobalMethod(sym) || (synthetic && isImplicitValue(sym, symtab)))
          enclosing(off).filterNot(_.symbol == sym).foreach { scope =>
            calls += CallEdge(scope.symbol, sym, doc.uri, line, synthetic)
          }

      doc.occurrences.foreach { o =>
        if (o.role.isReference && o.range.isDefined) {
          val r = o.range.get
          record(o.symbol, offset(r), r.startLine + 1, synthetic = false)
        }
      }
      doc.synthetics.foreach { s =>
        s.range.foreach { r =>
          syntheticSymbols(s.tree).distinct.foreach(sym => record(sym, offset(r), r.startLine + 1, synthetic = true))
        }
      }
    }

    // Targets not defined by this service (libraries, other services) are stored as external nodes.
    val dedupCalls = calls.distinct.toSeq
    val referenced = dedupCalls.map(_.callee).distinct
    val externals = referenced.filterNot(defined.contains).map { sym =>
      val info = symtab.get(sym)
      SymbolNode(
        symbol = sym,
        kind = info.flatMap(nodeKind).getOrElse(Syms.guessKind(sym)),
        name = if (Syms.isConstructor(sym)) "<init>" else Syms.name(sym),
        owner = sym.owner,
        display = Syms.display(sym),
        signature = info.map(Signatures.render(_, symtab)).getOrElse(""),
        service = None,
        file = None,
        line = None,
        isAbstract = info.exists(_.isAbstract)
      )
    }

    ServiceGraph(
      service = service,
      root = root.toString,
      symbols = defined.values.toSeq,
      externals = externals,
      extendsEdges = extendsE.toSeq,
      overrides = overridesE.toSeq,
      calls = dedupCalls
    )
  }

  // ---------------------------------------------------------------------------

  /**
   * A region of source attributed to `symbol`: a method/val body, or a class/object body
   * (`isInitializer`) whose statements run at construction time.
   */
  private final case class Scope(symbol: String, start: Int, end: Int, line: Int, isInitializer: Boolean, isDeclaration: Boolean)

  private val ignoredParents = Set("scala/AnyRef#", "scala/Any#", "java/lang/Object#", "scala/Product#", "scala/Serializable#", "java/io/Serializable#", "scala/Equals#")

  private def parse(uri: String, text: String, isScala3: Boolean): Option[Source] = {
    val input = Input.VirtualFile(uri, text)
    val dialectsToTry = if (isScala3) Seq(dialects.Scala3, dialects.Scala213Source3) else Seq(dialects.Scala213Source3, dialects.Scala3)
    dialectsToTry.iterator.map { d =>
      try d(input).parse[Source].toOption
      catch { case NonFatal(_) => None }
    }.collectFirst { case Some(s) => s }
  }

  private def collectScopes(source: Source, definitionAt: Map[Int, String]): Seq[Scope] = {
    val out = mutable.ArrayBuffer.empty[Scope]
    def at(name: scala.meta.Tree): Option[String] = definitionAt.get(name.pos.start)
    def add(sym: Option[String], tree: scala.meta.Tree, initializer: Boolean = false, declaration: Boolean = false): Unit =
      sym.foreach(s => out += Scope(s, tree.pos.start, tree.pos.end, tree.pos.startLine + 1, initializer, declaration))
    def firstPatName(pats: List[Pat]): Option[String] =
      pats.iterator.flatMap(_.collect { case v: Pat.Var => v.name }).flatMap(at).toSeq.headOption

    source.traverse {
      case d: Defn.Def          => add(at(d.name), d)
      case d: Defn.Macro        => add(at(d.name), d)
      case d: Defn.Val          => add(firstPatName(d.pats), d)
      case d: Defn.Var          => add(firstPatName(d.pats), d)
      case d: Defn.GivenAlias   => add(at(d.name), d)
      case d: Decl.Def          => add(at(d.name), d, declaration = true)
      case c: Ctor.Secondary    => add(definitionAt.get(c.pos.start).orElse(at(c.name)), c)
      case d: Defn.Class        => add(at(d.name).map(Syms.primaryConstructor), d, initializer = true)
      case d: Defn.Trait        => add(at(d.name).map(Syms.primaryConstructor), d, initializer = true)
      case d: Defn.Enum         => add(at(d.name).map(Syms.primaryConstructor), d, initializer = true)
      case d: Defn.Object       => add(at(d.name), d, initializer = true)
      case d: Pkg.Object        => add(at(d.name), d, initializer = true)
      case d: Defn.Given        => add(at(d.name), d, initializer = true)
    }
    out.toSeq
  }

  private def nodeKind(info: SymbolInformation): Option[SymbolKind] = {
    import SymbolInformation.{Kind => K, Property => P}
    def has(p: P): Boolean = (info.properties & p.value) != 0
    info.kind match {
      case K.TRAIT | K.INTERFACE       => Some(SymbolKind.Trait)
      case K.CLASS                     => Some(SymbolKind.Class)
      case K.OBJECT | K.PACKAGE_OBJECT => Some(SymbolKind.Object)
      case K.CONSTRUCTOR               => Some(SymbolKind.Constructor)
      case K.METHOD | K.MACRO          =>
        if (has(P.VAL) || has(P.VAR)) Some(SymbolKind.Value) else Some(SymbolKind.Method)
      case K.FIELD if !info.symbol.desc.isMethod => Some(SymbolKind.Value)
      case _                           => None
    }
  }

  private def isImplicitValue(sym: String, symtab: Map[String, SymbolInformation]): Boolean =
    sym.isGlobal && symtab.get(sym).exists { i =>
      val implicitish = SymbolInformation.Property.IMPLICIT.value | SymbolInformation.Property.GIVEN.value
      (i.properties & implicitish) != 0
    }

  /** Symbols a synthetic tree refers to, skipping `OriginalTree` (already covered by occurrences). */
  private def syntheticSymbols(tree: STree): Seq[String] = tree match {
    case IdTree(sym)                => Seq(sym)
    case SelectTree(qual, id)       => syntheticSymbols(qual) ++ id.toSeq.map(_.symbol)
    case ApplyTree(fn, args, _)     => syntheticSymbols(fn) ++ args.flatMap(syntheticSymbols)
    case TypeApplyTree(fn, _)       => syntheticSymbols(fn)
    case FunctionTree(_, body)      => syntheticSymbols(body)
    case MacroExpansionTree(b, _)   => syntheticSymbols(b)
    case _                          => Nil
  }

  private def computeLineStarts(text: String): Array[Int] = {
    val starts = mutable.ArrayBuffer(0)
    var i = 0
    while (i < text.length) {
      if (text.charAt(i) == '\n') starts += i + 1
      i += 1
    }
    starts.toArray
  }
}

/** Renders SemanticDB signatures as short, readable strings: `(query: Query): List[Hit]`. */
object Signatures {

  def render(info: SymbolInformation, symtab: Map[String, SymbolInformation]): String = info.signature match {
    case MethodSignature(_, paramLists, ret, _) =>
      paramLists.map(scope => params(scope, symtab).mkString("(", ", ", ")")).mkString + typeSuffix(ret)
    case ValueSignature(tpe) => typeSuffix(tpe)
    case _                   => ""
  }

  private def params(scope: SScope, symtab: Map[String, SymbolInformation]): Seq[String] = {
    val infos = if (scope.hardlinks.nonEmpty) scope.hardlinks else scope.symlinks.flatMap(symtab.get)
    infos.map { p =>
      val tpe = p.signature match {
        case ValueSignature(t) => renderType(t)
        case _                 => "?"
      }
      s"${p.displayName}: $tpe"
    }
  }

  private def typeSuffix(t: SType): String = {
    val r = renderType(t)
    if (r.isEmpty) "" else s": $r"
  }

  def renderType(t: SType): String = t match {
    case TypeRef(_, sym, args) =>
      val base = sym.desc.value
      if (args.isEmpty) base else args.map(renderType).mkString(s"$base[", ", ", "]")
    case SingleType(_, sym)         => s"${sym.desc.value}.type"
    case ByNameType(tpe)            => s"=> ${renderType(tpe)}"
    case RepeatedType(tpe)          => s"${renderType(tpe)}*"
    case AnnotatedType(_, tpe)      => renderType(tpe)
    case ExistentialType(tpe, _)    => renderType(tpe)
    case UniversalType(_, tpe)      => renderType(tpe)
    case WithType(types)            => types.map(renderType).mkString(" with ")
    case IntersectionType(types)    => types.map(renderType).mkString(" & ")
    case UnionType(types)           => types.map(renderType).mkString(" | ")
    case ConstantType(_)            => "literal"
    case _                          => ""
  }
}
