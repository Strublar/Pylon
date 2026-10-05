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

  test("query parameters are URL-decoded") {
    assertEquals(PylonServer.queryParams("sym=a%2FB%23m%28%29.&mode=up"), Map("sym" -> "a/B#m().", "mode" -> "up"))
  }
}
