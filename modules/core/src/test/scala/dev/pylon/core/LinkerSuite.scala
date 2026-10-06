package dev.pylon.core

class LinkerSuite extends munit.FunSuite {

  private def server(sym: String, svc: String, verb: String, path: String, protocol: String = "http", key: Option[String] = None) =
    Remote(sym, svc, Remote.Server, protocol, verb, path, key)
  private def client(sym: String, svc: String, verb: String, path: String, protocol: String = "http", key: Option[String] = None,
      hint: Option[String] = None) = Remote(sym, svc, Remote.Client, protocol, verb, path, key, hint)

  private val catalogItem  = server("cat-item", "catalog", "GET", "/catalog/items/{id}")
  private val catalogPost  = server("cat-post", "catalog", "POST", "/catalog/items")
  private val billingItem  = server("bill-item", "billing", "GET", "/catalog/items/{id}")
  private val gatewayItem  = server("gw-item", "gateway", "GET", "/catalog/items/{id}")
  private val services     = Seq("catalog", "billing", "gateway")

  private def links(all: Remote*)(rules: Linker.Rule*) =
    Linker.link(all, services, rules).map(l => (l.endpoint, l.confidence, l.reason)).sortBy(_._1)

  test("exact keys win: gRPC method, Kafka topic, Tapir endpoint") {
    val grpc = Seq(server("g", "catalog", "GRPC", "catalog.CatalogService/getItem", "grpc", Some("catalog.CatalogService/getItem")),
      client("c", "gateway", "GRPC", "catalog.CatalogService/getItem", "grpc", Some("catalog.CatalogService/getItem")))
    assertEquals(links(grpc: _*)(), Seq(("g", 1.0, "same gRPC method")))
    val kafka = Seq(server("k", "catalog", "CONSUME", "orders", "kafka", Some("orders")), client("p", "gateway", "PUBLISH", "orders", "kafka", Some("orders")),
      server("k2", "catalog", "CONSUME", "payments", "kafka", Some("payments")))
    assertEquals(links(kafka: _*)(), Seq(("k", 1.0, "same Kafka topic")))
    val tapir = Seq(server("t", "catalog", "GET", "/catalog/search", key = Some("catalog/shared/E.search.")),
      client("c", "gateway", "GET", "/catalog/search", key = Some("catalog/shared/E.search.")))
    assertEquals(links(tapir: _*)(), Seq(("t", 1.0, "same Tapir endpoint")))
  }

  test("HTTP: verb + path, parameters and unknown pieces match anything; other services preferred") {
    assertEquals(links(catalogItem, catalogPost, billingItem, gatewayItem, client("c", "gateway", "GET", "/catalog/items/{}"))(),
      Seq(("bill-item", 0.8, "HTTP verb + path"), ("cat-item", 0.8, "HTTP verb + path")))
    assertEquals(links(catalogItem, catalogPost, client("c", "gateway", "DELETE", "/catalog/items/{}"))(), Nil)
  }

  test("a hint naming a service narrows candidates and raises confidence") {
    val c = client("c", "gateway", "GET", "/catalog/items/{}", hint = Some("http://billing:8080"))
    assertEquals(links(catalogItem, billingItem, c)(), Seq(("bill-item", 0.9, "HTTP verb + path · hint → billing")))
    val viaRule = client("c", "gateway", "GET", "/catalog/items/{}", hint = Some("""config.getString("inventory.url")"""))
    assertEquals(links(catalogItem, billingItem, viaRule)(Linker.Rule("inventory.url", "catalog")).map(_._1), Seq("cat-item"))
  }

  test("suffix matches when the base URL holds a path prefix; own service only as a fallback") {
    assertEquals(links(catalogItem, client("c", "gateway", "GET", "/items/{}"))(), Seq(("cat-item", 0.5, "HTTP path suffix")))
    assertEquals(links(gatewayItem, client("c", "gateway", "GET", "/catalog/items/7"))().map(_._1), Seq("gw-item"))
  }

  test("hints only match whole words of service names") {
    assertEquals(Linker.hintedService(Some("http://catalogue:8080"), "gateway", services, Nil), None)
    assertEquals(Linker.hintedService(Some("""config.getString("catalog.url")"""), "gateway", services, Nil), Some("catalog"))
  }

  test("store: links are read like calls in both directions") {
    val store = GraphStore.inMemory()
    def node(sym: String, kind: SymbolKind, svc: String) =
      SymbolNode(sym, kind, sym, "", sym, "", Some(svc), Some("F.scala"), Some(1), isAbstract = false)
    store.replaceService(ServiceGraph("catalog", "/c", Seq(node("cat-item", SymbolKind.Endpoint, "catalog")), Nil, Nil, Nil, Nil, Seq(catalogItem)))
    store.replaceService(ServiceGraph("gateway", "/g", Seq(node("m", SymbolKind.Method, "gateway"), node("c", SymbolKind.Client, "gateway")), Nil, Nil, Nil,
      Seq(CallEdge("m", "c", "F.scala", 1, synthetic = false)), Seq(client("c", "gateway", "GET", "/catalog/items/{}"))))
    assertEquals(store.relink().size, 1)
    assertEquals(store.callees("c").map(c => (c.target.symbol, c.link.map(_.confidence))), Seq(("cat-item", Some(0.8))))
    assertEquals(store.callers("cat-item").map(_.caller.symbol), Seq("c"))
    assertEquals(store.entrypointPaths("cat-item").map(_.map(_.node.symbol)), Seq(Seq("m", "c", "cat-item")))
    store.close()
  }
}
