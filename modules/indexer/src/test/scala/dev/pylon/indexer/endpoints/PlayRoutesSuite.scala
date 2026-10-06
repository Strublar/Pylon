package dev.pylon.indexer.endpoints

import PlayRoutes._

class PlayRoutesSuite extends munit.FunSuite {

  test("parses routes, includes, comments and modifiers") {
    val lines = parse(
      """# comment
        |+ nocsrf
        |GET   /search              controllers.SearchController.search(q: String, page: Int ?= 1)
        |GET   /items/:id           controllers.ItemController.show(id: Long)
        |POST  /items               @controllers.ItemController.create
        |GET   /files/*path         controllers.Assets.at(path = "/public", file)
        |GET   /legacy/$id<[0-9]+>  controllers.ItemController.show(id: Long)
        |
        |->    /admin               admin.Routes
        |""".stripMargin
    )
    assertEquals(
      lines.map(_.content),
      Seq(
        RouteLine("GET", Seq("search"), "controllers.SearchController", "search", Some(2)),
        RouteLine("GET", Seq("items", "{id}"), "controllers.ItemController", "show", Some(1)),
        RouteLine("POST", Seq("items"), "controllers.ItemController", "create", None),
        RouteLine("GET", Seq("files", "{*path}"), "controllers.Assets", "at", Some(2)),
        RouteLine("GET", Seq("legacy", "{id}"), "controllers.ItemController", "show", Some(1)),
        Include(Seq("admin"), "admin")
      )
    )
    assertEquals(lines.map(_.lineNo), Seq(3, 4, 5, 6, 7, 9))
  }

  test("resolves actions on classes and objects, picking overloads by arity") {
    import scala.meta.internal.semanticdb._
    def method(params: Int) = SymbolInformation(
      signature = MethodSignature(parameterLists = Seq(Scope(symlinks = (1 to params).map(i => s"p$i"))))
    )
    val symtab = Map(
      "controllers/ItemController#show()."   -> method(1),
      "controllers/ItemController#show(+1)." -> method(2),
      "controllers/Health.check()."          -> method(0)
    )
    assertEquals(resolveAction("controllers.ItemController", "show", Some(2), symtab), Some("controllers/ItemController#show(+1)."))
    assertEquals(resolveAction("controllers.ItemController", "show", Some(1), symtab), Some("controllers/ItemController#show()."))
    assertEquals(resolveAction("controllers.Health", "check", None, symtab), Some("controllers/Health.check()."))
    assertEquals(resolveAction("controllers.Missing", "x", None, symtab), None)
  }
}
