package dev.pylon.indexer

import dev.pylon.core.{GraphStore, Linker, ServiceGraph}
import java.nio.file.Path

/** Indexes one sbt build (a "service") into a [[GraphStore]]. */
object Indexer {

  final case class Options(
      compile: Boolean = true,
      semanticdbVersion: Option[String] = None,
      linkRules: Seq[Linker.Rule] = Nil,
      log: String => Unit = Console.err.println
  )

  def extract(service: String, root: Path, options: Options = Options()): ServiceGraph = {
    val absRoot = root.toAbsolutePath.normalize
    if (options.compile) SbtRunner.compile(absRoot, options.semanticdbVersion, options.log)
    val docs = SemanticdbFiles.load(absRoot)
    if (docs.isEmpty)
      throw new IllegalStateException(
        s"No SemanticDB found under $absRoot. Did the build compile? (run without --no-compile, or enable semanticdbEnabled)"
      )
    options.log(s"[pylon] $service: ${docs.size} source files")
    Extractor.extract(service, absRoot, docs, options.log)
  }

  def index(store: GraphStore, service: String, root: Path, options: Options = Options()): ServiceGraph = {
    val graph = extract(service, root, options)
    store.replaceService(graph)
    val links = store.relink(options.linkRules)
    options.log(
      s"[pylon] $service: ${graph.symbols.size} symbols, ${graph.calls.size} calls, " +
        s"${graph.overrides.size} overrides, ${graph.externals.size} external targets; " +
        s"${graph.remotes.count(_.isClient)} outbound calls, ${links.size} links across services"
    )
    graph
  }
}
