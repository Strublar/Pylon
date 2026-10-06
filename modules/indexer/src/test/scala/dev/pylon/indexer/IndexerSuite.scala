package dev.pylon.indexer

import dev.pylon.core._
import java.nio.file.Paths

/**
 * Compiles and indexes the fixture builds (one Scala 2.13, one Scala 3 multi-project) and checks
 * the graph against the ProviderTrait example. Requires sbt (PYLON_SBT, set by the build to bin/sbt).
 */
abstract class FixtureSuite(fixture: String, pkg: String) extends munit.FunSuite {
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "min")

  private val repoRoot = Paths.get(sys.props.getOrElse("pylon.repoRoot", "."))
  private var store: GraphStore = _

  override def beforeAll(): Unit = {
    store = GraphStore.inMemory()
    Indexer.index(store, fixture, repoRoot.resolve(s"fixtures/$fixture"))
  }

  override def afterAll(): Unit = if (store != null) store.close()

  private def sym(q: String): SymbolNode =
    store.find(q).headOption.getOrElse(fail(s"no symbol for $q"))

  private def symbolOf(typeAndMember: String): String = {
    val Array(tpe, member) = typeAndMember.split('.')
    s"$pkg/$tpe#$member()."
  }

  test("trait method lists both implementations") {
    val target = sym("ProviderTrait.search")
    assertEquals(target.symbol, symbolOf("ProviderTrait.search"))
    assert(target.isAbstract, target)
    assertEquals(store.implementations(target.symbol).map(_.display), Seq("ProviderA.search", "ProviderB.search"))
  }

  test("ProviderA.search calls SearchServiceA.search, a fork with two implementations") {
    val callees = store.callees(sym("ProviderA.search").symbol).filterNot(_.target.isExternal)
    val ss = callees.find(_.target.display == "SearchServiceA.search").getOrElse(fail(s"missing call: $callees"))
    assert(ss.isFork)
    assertEquals(ss.candidates.map(_.display), Seq("CachedSearchServiceA.search", "ElasticSearchServiceA.search"))
    val ranker = callees.find(_.target.display == "Ranker.rank").getOrElse(fail("missing Ranker.rank"))
    assertEquals(ranker.candidates.map(_.display), Seq("BoostedRanker.rank", "DefaultRanker.rank"))
  }

  test("transitive overrides: BoostedRanker.rank implements Ranker.rank through DefaultRanker") {
    assertEquals(store.overridden(sym("BoostedRanker.rank").symbol).map(_.display).toSet, Set("DefaultRanker.rank", "Ranker.rank"))
  }

  test("callers go through the trait method") {
    val callers = store.callers(sym("ElasticSearchServiceA.search").symbol)
    assertEquals(callers.map(c => c.caller.display -> c.via.display).toSet, Set(
      "CachedSearchServiceA.search" -> "SearchServiceA.search",
      "ProviderA.search"            -> "SearchServiceA.search"
    ))
  }

  test("super calls are direct calls") {
    val callers = store.callers(sym("DefaultRanker.rank").symbol).map(_.caller.display)
    assert(callers.contains("BoostedRanker.rank"), callers)
  }

  test("calls inside for-comprehensions are attributed to the enclosing method") {
    val callees = store.callees(sym("ProviderB.search").symbol).map(_.target.display)
    assertEquals(callees.filter(c => c.startsWith("Tokenizer") || c.startsWith("InMemoryIndex")), Seq("Tokenizer.tokens", "InMemoryIndex.lookup"))
  }

  test("implicit arguments appear as synthetic calls") {
    val implicitCalls = store.callees(sym("DefaultRanker.rank").symbol).filter(_.synthetic).map(_.target.display)
    assert(implicitCalls.contains("Hit.byScoreDesc"), implicitCalls)
  }

  test("statements in a class body are attributed to its constructor") {
    val ctor = store.symbol(s"$pkg/SearchController#`<init>`().").getOrElse(fail("no constructor node"))
    val callees = store.callees(ctor.symbol).map(_.target.display)
    assert(callees.contains("Audit.record"), callees)
    // A val initializer belongs to the val itself.
    val valCallees = store.callees(s"$pkg/SearchController#defaultLimit.").map(_.target.display)
    assertEquals(valCallees, Seq("Defaults.limit"))
  }

  test("library calls are external") {
    val ext = store.callees(sym("DefaultRanker.rank").symbol).filter(_.target.isExternal).map(_.target.name)
    assert(ext.contains("sorted"), ext)
  }

  test("paths to entrypoints reach main through the forks") {
    val paths = store.entrypointPaths(sym("ElasticSearchServiceA.search").symbol).map(_.map(_.node.display))
    assert(paths.nonEmpty)
    assert(paths.forall(_.contains("SearchController.handle")), paths)
    assert(paths.forall(_.last == "ElasticSearchServiceA.search"), paths)
  }
}

class Scala213IndexerSuite extends FixtureSuite("search-213", "com/acme/legacysearch")
class Scala3IndexerSuite   extends FixtureSuite("search-3", "com/acme/search")
