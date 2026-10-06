package catalog.shared

import sttp.tapir.*

// Shared endpoint definitions (in a real system: a published library both services depend on).
object CatalogEndpoints:
  val search = endpoint.get.in("catalog" / "search").in(query[String]("q")).out(stringBody)
