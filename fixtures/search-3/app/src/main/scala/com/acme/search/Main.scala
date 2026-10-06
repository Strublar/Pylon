package com.acme.search

object Wiring:
  val controller: SearchController = SearchController(
    Map(
      "A" -> ProviderA(
        CachedSearchServiceA(ElasticSearchServiceA(FakeHttpClient()), Cache()),
        BoostedRanker(1.5)
      ),
      "B" -> ProviderB(InMemoryIndex(Map.empty))
    )
  )

@main def run(args: String*): Unit =
  Wiring.controller
    .handle(args.headOption.getOrElse("A"), args.drop(1).mkString(" "))
    .foreach(println)
