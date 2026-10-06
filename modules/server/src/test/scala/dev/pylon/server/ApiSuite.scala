package dev.pylon.server

import dev.pylon.core._
import java.nio.file.Files

class ApiSuite extends munit.FunSuite {

  private def method(sym: String, display: String, abstractM: Boolean = false, line: Int = 1, endLine: Option[Int] = None) =
    SymbolNode(sym, SymbolKind.Method, display.split('.').last, sym.takeWhile(_ != '#') + "#", display, "(q: Query): List[Hit]",
      Some("svc"), Some("src/P.scala"), Some(line), abstractM, endLine)

  private val pt = method("a/ProviderTrait#search().", "ProviderTrait.search", abstractM = true)
  private val pa = method("a/ProviderA#search().", "ProviderA.search", line = 3, endLine = Some(4))
  private val pb = method("a/ProviderB#search().", "ProviderB.search")
  private val h  = method("a/Controller#handle().", "Controller.handle")

  private val fixture = FunFixture[(Api, GraphStore)](
    setup = _ => {
      val root = Files.createTempDirectory("pylon-api")
      Files.createDirectories(root.resolve("src"))
      Files.write(root.resolve("src/P.scala"), "package a\n\nclass ProviderA:\n  def search(q: Query) = Nil\n".getBytes)
      val store = GraphStore.inMemory()
      store.replaceService(ServiceGraph("svc", root.toString, Seq(pt, pa, pb, h), Nil, Nil,
        Seq(pa.symbol -> pt.symbol, pb.symbol -> pt.symbol),
        Seq(CallEdge(h.symbol, pt.symbol, "src/C.scala", 7, synthetic = false))))
      (new Api(store), store)
    },
    teardown = _._2.close()
  )

  fixture.test("callees expose forks with their candidates") { case (api, _) =>
    val res = api.handle("/api/callees", Map("sym" -> h.symbol))
    assertEquals(res.status, 200)
    val Seq(c) = res.body.arr.toSeq
    assertEquals(c("target")("display").str, "ProviderTrait.search")
    assert(c("fork").bool)
    assertEquals(c("candidates").arr.map(_("ownerDisplay").str).toSeq, Seq("ProviderA", "ProviderB"))
    assertEquals(c("sites").arr.map(_("line").num.toInt).toSeq, Seq(7))
  }

  fixture.test("callers report the method named at the call site") { case (api, _) =>
    val Seq(c) = api.handle("/api/callers", Map("sym" -> pa.symbol)).body.arr.toSeq
    assertEquals(c("caller")("display").str, "Controller.handle")
    assertEquals(c("via")("display").str, "ProviderTrait.search")
  }

  fixture.test("node details include implementations and overrides") { case (api, _) =>
    val body = api.handle("/api/node", Map("sym" -> pt.symbol)).body
    assertEquals(body("implementations").arr.map(_("display").str).toSeq, Seq("ProviderA.search", "ProviderB.search"))
    val impl = api.handle("/api/node", Map("sym" -> pa.symbol)).body
    assertEquals(impl("overrides").arr.map(_("display").str).toSeq, Seq("ProviderTrait.search"))
  }

  fixture.test("source returns the definition lines from the service root") { case (api, _) =>
    val body = api.handle("/api/source", Map("sym" -> pa.symbol)).body
    assertEquals(body("focusLine").num.toInt, 3)
    assert(body("lines").arr.map(_.str).exists(_.contains("def search")))
  }

  fixture.test("source never reads outside the service root") { case (api, store) =>
    store.replaceService(ServiceGraph("svc", "/tmp", Seq(pa.copy(file = Some("../etc/passwd"))), Nil, Nil, Nil, Nil))
    assertEquals(api.handle("/api/source", Map("sym" -> pa.symbol)).status, 404)
  }

  fixture.test("errors: missing and unknown symbols") { case (api, _) =>
    assertEquals(api.handle("/api/callees", Map.empty).status, 400)
    assertEquals(api.handle("/api/callees", Map("sym" -> "nope")).status, 404)
    assertEquals(api.handle("/api/whatever", Map.empty).status, 404)
  }

  fixture.test("endpoints are listed and found by path") { case (api, store) =>
    val ep = SymbolNode("pylon:endpoint/svc/http4s/GET /items/{id}", SymbolKind.Endpoint, "GET /items/{id}", "", "GET /items/{id}",
      "http4s", Some("svc"), Some("src/P.scala"), Some(3), isAbstract = false)
    store.replaceService(ServiceGraph("svc", "/tmp", Seq(pa, ep), Nil, Nil, Nil,
      Seq(CallEdge(ep.symbol, pa.symbol, "src/P.scala", 3, synthetic = false))))
    val list = api.handle("/api/endpoints", Map.empty).body.arr.toSeq
    assertEquals(list.map(_("display").str), Seq("GET /items/{id}"))
    assertEquals(api.handle("/api/search", Map("q" -> "GET /items/7")).body.arr.map(_("kind").str).toSeq, Seq("endpoint"))
    assertEquals(api.handle("/api/callers", Map("sym" -> pa.symbol)).body.arr.map(_("caller")("display").str).toSeq, Seq("GET /items/{id}"))
  }

  fixture.test("links are exposed on callees and callers") { case (api, store) =>
    val ep = SymbolNode("e", SymbolKind.Endpoint, "GET /items/{id}", "", "GET /items/{id}", "http4s", Some("other"), Some("x"), Some(1), isAbstract = false)
    val cl = SymbolNode("c", SymbolKind.Client, "→ HTTP GET /items/{}", "", "→ HTTP GET /items/{}", "sttp", Some("svc2"), Some("y"), Some(2), isAbstract = false)
    store.replaceService(ServiceGraph("other", "/o", Seq(ep), Nil, Nil, Nil, Nil, Seq(Remote("e", "other", Remote.Server, "http", "GET", "/items/{id}"))))
    store.replaceService(ServiceGraph("svc2", "/s", Seq(cl), Nil, Nil, Nil, Nil, Seq(Remote("c", "svc2", Remote.Client, "http", "GET", "/items/{}"))))
    store.relink()
    val Seq(c) = api.handle("/api/callees", Map("sym" -> "c")).body.arr.toSeq
    assertEquals(c("target")("display").str, "GET /items/{id}")
    assertEquals(c("link")("confidence").num, 0.8)
    val Seq(back) = api.handle("/api/callers", Map("sym" -> "e")).body.arr.toSeq
    assertEquals(back("caller")("display").str, "→ HTTP GET /items/{}")
    assertEquals(api.handle("/api/links", Map.empty).body.arr.size, 1)
  }

  test("query parameters are URL-decoded") {
    assertEquals(PylonServer.queryParams("sym=a%2FB%23m%28%29.&mode=up"), Map("sym" -> "a/B#m().", "mode" -> "up"))
  }
}
