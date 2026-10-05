package com.acme.legacysearch

object Wiring {
  val controller: SearchController = new SearchController(
    Map(
      "A" -> new ProviderA(
        new CachedSearchServiceA(new ElasticSearchServiceA(new FakeHttpClient), new Cache),
        new BoostedRanker(1.5)
      ),
      "B" -> new ProviderB(new InMemoryIndex(Map.empty))
    )
  )
}

object Main {
  def main(args: Array[String]): Unit =
    Wiring.controller
      .handle(args.headOption.getOrElse("A"), args.drop(1).mkString(" "))
      .foreach(println)
}
