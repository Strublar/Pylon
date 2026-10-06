package dev.pylon.indexer

import dev.pylon.core._
import java.nio.file.Paths

/**
 * Two services (fixtures/system): `gateway` calls `catalog` through sttp, the http4s client, the Pekko
 * HTTP client, Play WS, a Tapir client, an fs2-grpc client (ScalaPB server) and Kafka.
 */
class SystemSuite extends munit.FunSuite {
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(20, "min")

  private val repoRoot = Paths.get(sys.props.getOrElse("pylon.repoRoot", "."))
  private var store: GraphStore = _

  override def beforeAll(): Unit = {
    store = GraphStore.inMemory()
    // Index the calling service first: links must not depend on the order.
    Indexer.index(store, "gateway", repoRoot.resolve("fixtures/system/gateway"))
    Indexer.index(store, "catalog", repoRoot.resolve("fixtures/system/catalog"))
  }

  override def afterAll(): Unit = if (store != null) store.close()

  private def clientsOf(method: String): Seq[SymbolNode] =
    store.callees(store.find(method).head.symbol).map(_.target).filter(_.kind == SymbolKind.Client)

  private def reached(method: String): Seq[(String, String, Double, String)] =
    clientsOf(method).flatMap(c => store.callees(c.symbol)).flatMap { l =>
      l.link.map(k => (l.target.service.getOrElse(""), l.target.display, k.confidence, k.reason))
    }

  test("every client in the gateway reaches the right catalog endpoint") {
    val hint = " · hint → catalog"
    assertEquals(reached("gateway.CatalogClients.itemViaSttp"), Seq(("catalog", "GET /catalog/items/{id}", 0.9, s"HTTP verb + path$hint")))
    assertEquals(reached("gateway.CatalogClients.itemViaHttp4s"), Seq(("catalog", "GET /catalog/items/{id}", 0.9, s"HTTP verb + path$hint")))
    assertEquals(reached("gateway.CatalogClients.createViaPekko"), Seq(("catalog", "POST /catalog/items", 0.9, s"HTTP verb + path$hint")))
    assertEquals(reached("gateway.CatalogClients.itemViaPlayWs"), Seq(("catalog", "GET /catalog/items/{id}", 0.9, s"HTTP verb + path$hint")))
    assertEquals(reached("gateway.CatalogClients.searchViaTapir"), Seq(("catalog", "GET /catalog/search", 1.0, "same Tapir endpoint")))
    assertEquals(reached("gateway.CatalogClients.itemViaGrpc"), Seq(("catalog", "GRPC catalog.CatalogService/getItem", 1.0, "same gRPC method")))
    assertEquals(reached("gateway.CatalogClients.publishOrder"), Seq(("catalog", "CONSUME orders", 1.0, "same Kafka topic")))
  }

  test("client nodes describe the call") {
    assertEquals(clientsOf("gateway.CatalogClients.itemViaSttp").map(c => (c.display, c.signature)), Seq(("→ HTTP GET /catalog/items/{}", "sttp")))
    assertEquals(clientsOf("gateway.CatalogClients.publishOrder").map(_.display), Seq("→ Kafka publish orders"))
  }

  test("gRPC: the endpoint leads to the implementation; generated glue is never a caller") {
    val grpc = store.find("GRPC catalog.CatalogService/getItem")
    assertEquals(grpc.map(_.display), Seq("GRPC catalog.CatalogService/getItem"))
    assertEquals(store.callees(grpc.head.symbol).map(_.target.display), Seq("CatalogServiceImpl.getItem"))
    val impl = store.find("catalog.app.CatalogServiceImpl.getItem").head
    assertEquals(store.callers(impl.symbol).map(_.caller.display), Seq("GRPC catalog.CatalogService/getItem"))
    // The gateway calls the client node, not the generated stub.
    val viaGrpc = store.callees(store.find("gateway.CatalogClients.itemViaGrpc").head.symbol).filterNot(_.target.isExternal)
    assertEquals(viaGrpc.map(_.target.kind), Seq(SymbolKind.Client))
  }

  test("kafka: the consumer endpoint leads to both subscribing methods") {
    val consume = store.find("CONSUME orders").head
    assertEquals(store.callees(consume.symbol).map(_.target.display).sorted, Seq("Fs2OrderConsumer.stream", "OrderConsumer.run"))
  }

  test("upward walks cross services: a catalog repository method is reached from a gateway endpoint") {
    val find  = store.find("catalog.app.PostgresItemRepository.find").head
    val roots = store.entrypointPaths(find.symbol).map(_.head.node)
    assert(roots.exists(r => r.display == "GET /checkout/{id}" && r.service.contains("gateway")), roots.map(_.display))
  }

  test("re-indexing a service keeps links consistent") {
    Indexer.index(store, "catalog", repoRoot.resolve("fixtures/system/catalog"), Indexer.Options(compile = false))
    assertEquals(store.links().size, 7)
  }
}
