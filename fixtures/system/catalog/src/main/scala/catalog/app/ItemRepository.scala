package catalog.app

trait ItemRepository:
  def find(id: Long): Option[String]
  def save(name: String): Long
  def search(q: String): List[String]
  def reserve(orderId: String): Unit

class PostgresItemRepository extends ItemRepository:
  def find(id: Long): Option[String] = Some(s"item-$id")
  def save(name: String): Long = name.length.toLong
  def search(q: String): List[String] = List(q)
  def reserve(orderId: String): Unit = println(s"reserved $orderId")

class InMemoryItemRepository extends ItemRepository:
  private val items = scala.collection.mutable.Map.empty[Long, String]
  def find(id: Long): Option[String] = items.get(id)
  def save(name: String): Long = { items(items.size.toLong) = name; items.size.toLong }
  def search(q: String): List[String] = items.values.filter(_.contains(q)).toList
  def reserve(orderId: String): Unit = ()
