package com.acme.legacysearch

class SearchController(providers: Map[String, ProviderTrait]) {
  private val defaultLimit: Int = Defaults.limit()

  // Runs in the constructor: attributed to SearchController's initializer.
  Audit.record("controller-created")

  def handle(providerName: String, text: String): List[Hit] =
    providers(providerName).search(Query(text, defaultLimit))
}

object Defaults {
  def limit(): Int = 10
}

object Audit {
  def record(event: String): Unit = println(event)
}
