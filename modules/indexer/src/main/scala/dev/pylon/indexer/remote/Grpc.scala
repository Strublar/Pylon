package dev.pylon.indexer.remote

import scala.meta.internal.semanticdb.{SymbolInformation, TextDocument}
import scala.meta.internal.semanticdb.Scala._

/**
 * gRPC through generated code (ScalaPB grpc-java, fs2-grpc, akka-grpc, pekko-grpc, zio-grpc).
 *
 * Generated gRPC files are recognised by location (`src_managed`) and package references. Their
 * abstract methods are the rpc methods; generated concrete methods overriding them are stubs. A project
 * method overriding an rpc method implements it (server side); a project call to an rpc or stub method
 * is a client call. Both sides share a key `<proto package>.<Service>/<method>`, normalised so that a
 * ScalaPB server and an fs2-grpc client of the same .proto agree.
 */
final class Grpc(docs: Seq[TextDocument], symtab: Map[String, SymbolInformation]) {

  import Grpc._

  val generatedFiles: Set[String] = docs.filter(isGeneratedGrpc).map(_.uri).toSet

  private val generatedSymbols: Set[String] =
    docs.filter(d => generatedFiles(d.uri)).flatMap(_.symbols.map(_.symbol)).filter(_.isGlobal).toSet

  private def isMethod(i: SymbolInformation): Boolean =
    i.kind == SymbolInformation.Kind.METHOD && i.symbol.desc.value != "<init>"

  /** Abstract methods declared in generated gRPC files: the rpc methods, by key. */
  private val rpcKeys: Map[String, String] =
    generatedSymbols.iterator
      .flatMap(symtab.get)
      .filter(i => isMethod(i) && (i.properties & SymbolInformation.Property.ABSTRACT.value) != 0)
      .filterNot(i => Ignored(i.symbol.desc.value))
      .flatMap(i => keyOf(i.symbol).map(i.symbol -> _))
      .toMap

  private def overriddenClosure(sym: String, seen: Set[String] = Set.empty): Set[String] =
    symtab.get(sym).toSeq.flatMap(_.overriddenSymbols).filterNot(seen).toSet.flatMap { (o: String) =>
      overriddenClosure(o, seen + sym) + o
    }

  /** The rpc key of a method a client may call: an rpc method or a generated stub method. */
  def clientKey(callee: String): Option[String] =
    rpcKeys.get(callee).orElse {
      if (generatedSymbols(callee)) overriddenClosure(callee).collectFirst(Function.unlift(rpcKeys.get))
      else None
    }

  /** Project methods implementing rpc methods: (implementation, key). */
  def implementations(projectSymbols: Iterable[String]): Seq[(String, String)] =
    projectSymbols.iterator
      .filterNot(generatedSymbols)
      .flatMap(s => overriddenClosure(s).collectFirst(Function.unlift(rpcKeys.get)).map(s -> _))
      .toSeq
}

object Grpc {

  private val Packages = Seq("io/grpc/", "scalapb/grpc/", "fs2/grpc/", "akka/grpc/", "org/apache/pekko/grpc/", "scalapb/zio_grpc/")

  /** Companion and plumbing members of generated services, not rpc methods. */
  private val Ignored = Set("serviceCompanion", "bindService", "build", "javaDescriptor", "scalaDescriptor", "close", "closed")

  def isGeneratedGrpc(d: TextDocument): Boolean =
    d.uri.split('/').contains("src_managed") && d.occurrences.exists(o => Packages.exists(o.symbol.startsWith))

  private val Suffixes = Seq("BlockingStub", "BlockingClient", "PowerApiHandler", "PowerApi", "Fs2Grpc", "Stub", "Client", "Grpc", "Handler")

  /**
   * `catalog/CatalogServiceGrpc.CatalogService#getItem().` -> `catalog.CatalogService/getItem`;
   * `catalog/CatalogServiceFs2Grpc#getItem().` -> the same.
   */
  def keyOf(method: String): Option[String] = {
    val owners = method.ownerChain.dropRight(1)
    val pkg    = owners.filter(_.isPackage).lastOption.map(_.stripSuffix("/").replace('/', '.')).getOrElse("")
    val types  = owners.filterNot(_.isPackage).map(_.desc.value)
    types.lastOption.map { innermost =>
      val noZ = if (innermost.length > 1 && innermost.head == 'Z' && innermost(1).isUpper) innermost.drop(1) else innermost
      val service = Suffixes.foldLeft(noZ)((n, suffix) => if (n.endsWith(suffix) && n.length > suffix.length) n.dropRight(suffix.length) else n)
      s"${if (pkg.isEmpty) "" else s"$pkg."}$service/${method.desc.value}"
    }
  }
}
