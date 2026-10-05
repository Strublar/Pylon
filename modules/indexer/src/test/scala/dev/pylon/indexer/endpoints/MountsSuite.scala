package dev.pylon.indexer.endpoints

class MountsSuite extends munit.FunSuite {

  private def route(holder: String, segs: String*) = Route("fw", "GET", segs, Some(holder), "F.scala", 1, 1)
  private def paths(routes: Seq[Route], mounts: Seq[Mount]) = Mounts.resolve("svc", routes, mounts).map(_.path).sorted

  test("unmounted holders are at the root") {
    assertEquals(paths(Seq(route("a/R.routes.", "items", "{id}")), Nil), Seq("/items/{id}"))
    assertEquals(paths(Seq(route("a/R.routes.")), Nil), Seq("/"))
  }

  test("explicit mounts chain prefixes") {
    val mounts = Seq(Mount("app", Seq("api"), "v1", explicit = true), Mount("v1", Seq("v1"), "items", explicit = true))
    assertEquals(paths(Seq(route("items", "items")), mounts), Seq("/api/v1/items"))
  }

  test("implicit references only count without explicit mounts") {
    val r = Seq(route("items", "items"))
    assertEquals(paths(r, Seq(Mount("all", Nil, "items", explicit = false), Mount("app", Seq("api"), "all", explicit = true))), Seq("/api/items"))
    // An explicit mount wins over a plain reference (e.g. from a test).
    assertEquals(paths(r, Seq(Mount("test", Nil, "items", explicit = false), Mount("app", Seq("api"), "items", explicit = true))), Seq("/api/items"))
  }

  test("a holder mounted twice gets both paths; cycles terminate") {
    val r = Seq(route("items", "items"))
    assertEquals(
      paths(r, Seq(Mount("app", Seq("v1"), "items", explicit = true), Mount("app", Seq("v2"), "items", explicit = true))),
      Seq("/v1/items", "/v2/items")
    )
    assertEquals(paths(r, Seq(Mount("a", Seq("x"), "items", explicit = true), Mount("items", Seq("y"), "a", explicit = true))), Seq("/x/items"))
  }

  test("endpoint symbols are unique per service, framework, verb and path") {
    assertEquals(Mounts.endpointSymbol("svc", "http4s", "GET", "/a/{id}"), "pylon:endpoint/svc/http4s/GET /a/{id}")
  }
}
