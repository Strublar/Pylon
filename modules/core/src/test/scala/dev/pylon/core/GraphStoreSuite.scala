package dev.pylon.core

class GraphStoreSuite extends munit.FunSuite {

  private def method(sym: String, display: String, abstractM: Boolean = false, service: Option[String] = Some("svc")) =
    SymbolNode(sym, SymbolKind.Method, display.split('.').last, sym.takeWhile(_ != '#') + "#", display, "()", service,
      service.map(_ => "F.scala"), service.map(_ => 1), abstractM)

  // ProviderTrait.search <- ProviderA.search, ProviderB.search
  // ProviderA.search -> SearchServiceA.search <- Elastic.search, Cached.search -> SearchServiceA.search
  // Controller.handle -> ProviderTrait.search ; Main.main -> Controller.handle ; Elastic.search -> List.map (external)
  private val ptSearch   = method("a/ProviderTrait#search().", "ProviderTrait.search", abstractM = true)
  private val paSearch   = method("a/ProviderA#search().", "ProviderA.search")
  private val pbSearch   = method("a/ProviderB#search().", "ProviderB.search")
  private val ssSearch   = method("a/SearchServiceA#search().", "SearchServiceA.search", abstractM = true)
  private val elSearch   = method("a/Elastic#search().", "Elastic.search")
  private val caSearch   = method("a/Cached#search().", "Cached.search")
  private val handle     = method("a/Controller#handle().", "Controller.handle")
  private val main       = method("a/Main.main().", "Main.main")
  private val listMap    = method("scala/List#map().", "List.map", service = None)

  private def graph = ServiceGraph(
    service = "svc",
    root = "/tmp/svc",
    symbols = Seq(ptSearch, paSearch, pbSearch, ssSearch, elSearch, caSearch, handle, main),
    externals = Seq(listMap),
    extendsEdges = Seq("a/ProviderA#" -> "a/ProviderTrait#"),
    overrides = Seq(
      paSearch.symbol -> ptSearch.symbol,
      pbSearch.symbol -> ptSearch.symbol,
      elSearch.symbol -> ssSearch.symbol,
      caSearch.symbol -> ssSearch.symbol
    ),
    calls = Seq(
      CallEdge(paSearch.symbol, ssSearch.symbol, "F.scala", 10, synthetic = false),
      CallEdge(caSearch.symbol, ssSearch.symbol, "F.scala", 20, synthetic = false),
      CallEdge(elSearch.symbol, listMap.symbol, "F.scala", 30, synthetic = false),
      CallEdge(handle.symbol, ptSearch.symbol, "F.scala", 40, synthetic = false),
      CallEdge(main.symbol, handle.symbol, "F.scala", 50, synthetic = false)
    )
  )

  private val store = FunFixture[GraphStore](
    setup = _ => { val s = GraphStore.inMemory(); s.replaceService(graph); s },
    teardown = _.close()
  )

  store.test("implementations of an abstract method are its concrete overriders") { s =>
    assertEquals(s.implementations(ptSearch.symbol).map(_.display), Seq("ProviderA.search", "ProviderB.search"))
    assertEquals(s.implementations(paSearch.symbol).map(_.display), Seq("ProviderA.search"))
  }

  store.test("callees mark abstract targets with several implementations as forks") { s =>
    val Seq(c) = s.callees(paSearch.symbol)
    assertEquals(c.target.display, "SearchServiceA.search")
    assert(c.isFork)
    assertEquals(c.candidates.map(_.display), Seq("Cached.search", "Elastic.search"))
    assertEquals(c.sites, Seq("F.scala" -> 10))
  }

  store.test("external callees are kept and flagged") { s =>
    val Seq(c) = s.callees(elSearch.symbol)
    assert(c.target.isExternal)
    assert(!c.isFork)
  }

  store.test("callers include calls made through an overridden method") { s =>
    val callers = s.callers(elSearch.symbol)
    assertEquals(callers.map(c => c.caller.display -> c.via.display), Seq(
      "Cached.search"    -> "SearchServiceA.search",
      "ProviderA.search" -> "SearchServiceA.search"
    ))
    assertEquals(s.callers(elSearch.symbol, viaOverrides = false), Nil)
  }

  store.test("entrypoint paths walk up to roots, root first") { s =>
    val paths = s.entrypointPaths(elSearch.symbol).map(_.map(_.node.display))
    assertEquals(paths.toSet, Set(
      Seq("Main.main", "Controller.handle", "ProviderA.search", "Elastic.search"),
      Seq("Main.main", "Controller.handle", "ProviderA.search", "Cached.search", "Elastic.search")
    ))
    val direct = s.entrypointPaths(elSearch.symbol).find(_.size == 4).get
    assertEquals(direct.map(_.via.map(_.display)), Seq(None, Some("Controller.handle"), Some("ProviderTrait.search"), Some("SearchServiceA.search")))
  }

  store.test("find ranks exact display matches first and accepts Type#method") { s =>
    assertEquals(s.find("ProviderA.search").headOption.map(_.symbol), Some(paSearch.symbol))
    assertEquals(s.find("ProviderA#search").headOption.map(_.symbol), Some(paSearch.symbol))
    assertEquals(s.find("a/ProviderA#search().").headOption.map(_.symbol), Some(paSearch.symbol))
    assertEquals(s.find("search").take(1).map(_.name), Seq("search"))
  }

  store.test("re-indexing a service replaces its rows") { s =>
    s.replaceService(graph.copy(calls = graph.calls.take(1)))
    assertEquals(s.callers(handle.symbol), Nil)
    assertEquals(s.callees(paSearch.symbol).size, 1)
  }

  store.test("a symbol another service references survives as external when its service is re-indexed away") { s =>
    val other = ServiceGraph("other", "/tmp/o", Seq(method("b/X#run().", "X.run", service = Some("other"))),
      Seq(paSearch.copy(service = None)), Nil, Nil, Seq(CallEdge("b/X#run().", paSearch.symbol, "X.scala", 1, synthetic = false)))
    s.replaceService(other)
    assertEquals(s.symbol(paSearch.symbol).flatMap(_.service), Some("svc"))
    s.replaceService(graph.copy(
      symbols = graph.symbols.filterNot(_ == paSearch),
      overrides = graph.overrides.filterNot(_._1 == paSearch.symbol),
      calls = graph.calls.filterNot(_.caller == paSearch.symbol)
    ))
    assertEquals(s.symbol(paSearch.symbol).map(_.isExternal), Some(true))
    assertEquals(s.callers(paSearch.symbol).map(_.caller.display), Seq("X.run"))
  }
}
