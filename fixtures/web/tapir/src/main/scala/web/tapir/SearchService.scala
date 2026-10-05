package web.tapir

trait SearchService {
  def search(q: String): List[String]
  def find(id: Long): Option[String]
  def delete(id: Long): String
  def reindex(): String
}

class ElasticSearchService extends SearchService {
  def search(q: String): List[String] = List(s"elastic:$q")
  def find(id: Long): Option[String] = Some(s"doc-$id")
  def delete(id: Long): String = s"deleted $id"
  def reindex(): String = "reindexing"
}

class CachedSearchService(underlying: SearchService) extends SearchService {
  private val cache = scala.collection.mutable.Map.empty[String, List[String]]
  def search(q: String): List[String] = cache.getOrElseUpdate(q, underlying.search(q))
  def find(id: Long): Option[String] = underlying.find(id)
  def delete(id: Long): String = { cache.clear(); underlying.delete(id) }
  def reindex(): String = { cache.clear(); underlying.reindex() }
}
