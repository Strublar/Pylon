package dev.pylon.indexer

import dev.pylon.core._
import java.nio.file.Paths

/**
 * Compiles and indexes fixtures/web: one subproject per HTTP framework (Play, http4s, Tapir,
 * ZIO HTTP, Pekko HTTP, Akka HTTP), each routing into a SearchService trait with two implementations.
 */
class EndpointsSuite extends munit.FunSuite {
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(20, "min")

  private val repoRoot = Paths.get(sys.props.getOrElse("pylon.repoRoot", "."))
  private var store: GraphStore = _

  override def beforeAll(): Unit = {
    store = GraphStore.inMemory()
    Indexer.index(store, "web", repoRoot.resolve("fixtures/web"))
  }

  override def afterAll(): Unit = if (store != null) store.close()

  private def endpoint(display: String, framework: String): SymbolNode =
    store.endpoints().find(e => e.display == display && e.signature == framework).getOrElse(fail(s"no $framework endpoint $display"))

  test("finds every route of every framework, with mounted prefixes") {
    val found = store.endpoints().map(e => s"${e.signature} ${e.display}").sorted
    assertEquals(
      found,
      Seq(
        "akka-http GET /health",
        "akka-http GET /search/{q}",
        "http4s DELETE /api/items/{id}",
        "http4s GET /api/items/{id}",
        "http4s GET /api/search",
        "http4s GET /health",
        "pekko-http DELETE /api/items/{id}",
        "pekko-http GET /api/items/{id}",
        "pekko-http GET /api/search",
        "pekko-http POST /api/admin/reindex",
        "play GET /files/{*path}",
        "play GET /items/{id}",
        "play GET /legacy/{id}",
        "play GET /search",
        "play POST /admin/reindex",
        "tapir GET /api/v1/items/{id}",
        "tapir GET /api/v1/search",
        "tapir POST /api/v1/admin/reindex",
        "zio-http DELETE /items/{id}",
        "zio-http GET /items/{id}",
        "zio-http GET /search/{q}"
      )
    )
  }

  private def calleeNames(e: SymbolNode): Seq[String] = store.callees(e.symbol).filterNot(_.target.isExternal).map(_.target.display)

  test("inline handlers: the endpoint calls the service trait, a fork with two implementations") {
    for (fw <- Seq("http4s", "pekko-http")) {
      val c = store.callees(endpoint("GET /api/search", fw).symbol).find(_.target.display == "SearchService.search")
        .getOrElse(fail(s"$fw: no call to SearchService.search"))
      assert(c.isFork, c)
      assertEquals(c.candidates.map(_.display), Seq("CachedSearchService.search", "ElasticSearchService.search"))
    }
    assertEquals(calleeNames(endpoint("GET /search/{q}", "zio-http")), Seq("SearchService.search"))
    assertEquals(calleeNames(endpoint("GET /search/{q}", "akka-http")), Seq("SearchService.search"))
    assertEquals(calleeNames(endpoint("DELETE /api/items/{id}", "pekko-http")), Seq("SearchService.delete"))
  }

  test("tapir: server logic belongs to the endpoint, including calls to helper methods") {
    assertEquals(calleeNames(endpoint("GET /api/v1/search", "tapir")), Seq("SearchService.search"))
    assertEquals(calleeNames(endpoint("GET /api/v1/items/{id}", "tapir")), Seq("ItemHandlers.show"))
  }

  test("play: routes point at controller actions; generated router code is not a caller") {
    assertEquals(calleeNames(endpoint("GET /items/{id}", "play")), Seq("ItemController.show"))
    assertEquals(calleeNames(endpoint("POST /admin/reindex", "play")), Seq("AdminController.reindex"))
    val show = store.find("controllers.ItemController.show").head
    assertEquals(store.callers(show.symbol).map(_.caller.display).sorted, Seq("GET /items/{id}", "GET /legacy/{id}"))
  }

  test("upward walks end at endpoints, through the trait") {
    val elastic = store.find("web.pekko.ElasticSearchService.reindex").head
    val roots   = store.entrypointPaths(elastic.symbol).map(_.head.node)
    assert(roots.nonEmpty)
    assert(roots.forall(r => r.kind == SymbolKind.Endpoint && r.display == "POST /api/admin/reindex"), roots)
  }

  test("routes no longer attribute their calls to the val that holds them") {
    val holder = store.find("web.http4s.SearchRoutes.routes").head
    assertEquals(store.callees(holder.symbol).filterNot(_.target.isExternal).map(_.target.display), Nil)
  }

  test("endpoint queries ignore parameter names and accept concrete values") {
    assertEquals(store.find("GET /api/v1/items/42").map(_.signature), Seq("tapir"))
    assertEquals(store.find("/legacy/{x}").map(_.display), Seq("GET /legacy/{id}"))
    assertEquals(store.find("DELETE /items/:id").map(_.signature), Seq("zio-http"))
  }
}
