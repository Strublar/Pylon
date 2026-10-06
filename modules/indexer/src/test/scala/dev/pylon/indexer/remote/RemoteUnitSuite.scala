package dev.pylon.indexer.remote

import dev.pylon.indexer.endpoints.ParsedFile
import scala.meta._
import scala.meta.internal.semanticdb.TextDocument

class RemoteUnitSuite extends munit.FunSuite {

  private def evalPath(code: String): (String, Option[String]) = {
    val src  = s"object A { val x = $code }"
    val tree = dialects.Scala3(src).parse[Source].get
    val file = new ParsedFile("A.scala", src, tree, TextDocument())
    val rhs  = tree.collect { case d: Defn.Val => d.rhs }.head
    UrlTemplate.toPath(UrlTemplate.eval(file, rhs, new UrlTemplate.Vals(Seq(file))))
  }

  test("url templates: interpolation, concatenation, hosts, base urls and query strings") {
    assertEquals(evalPath("""s"$base/catalog/items/$id""""), ("/catalog/items/{}", Some("base")))
    assertEquals(evalPath("""uri"http://catalog:8080/items/$id?full=true""""), ("/items/{}", Some("http://catalog:8080")))
    assertEquals(evalPath(""""/catalog/" + id + "/stock""""), ("/catalog/{}/stock", None))
    assertEquals(evalPath(""""https://api.example.com""""), ("/", Some("https://api.example.com")))
    assertEquals(evalPath("""s"${conf.url}/a//b/""""), ("/a/b", Some("conf.url")))
  }

  test("gRPC keys agree across generators") {
    val keys = Seq(
      "catalog/CatalogServiceGrpc.CatalogService#getItem().",
      "catalog/CatalogServiceGrpc.CatalogServiceBlockingClient#getItem().",
      "catalog/CatalogServiceGrpc.CatalogServiceStub#getItem().",
      "catalog/CatalogServiceFs2Grpc#getItem().",
      "catalog/ZioCatalog.ZCatalogService#getItem().",
      "catalog/ZioCatalog.CatalogServiceClient#getItem()."
    ).map(Grpc.keyOf)
    assertEquals(keys.distinct, Seq(Some("catalog.CatalogService/getItem")))
    assertEquals(Grpc.keyOf("com/acme/proto/SearchServiceGrpc.SearchService#search()."), Some("com.acme.proto.SearchService/search"))
  }
}
