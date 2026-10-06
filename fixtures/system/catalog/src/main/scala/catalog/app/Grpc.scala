package catalog.app

import catalog.{CatalogServiceGrpc, GetItemRequest, Item}
import scala.concurrent.Future

class CatalogServiceImpl(repo: ItemRepository) extends CatalogServiceGrpc.CatalogService:
  def getItem(request: GetItemRequest): Future[Item] =
    Future.successful(Item(request.id, repo.find(request.id).getOrElse("")))
