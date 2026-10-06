package controllers

import javax.inject.Inject
import play.api.mvc._
import services.SearchService

class SearchController @Inject() (cc: ControllerComponents, service: SearchService) extends AbstractController(cc) {
  def search(q: String): Action[AnyContent] = Action {
    Ok(service.search(q).mkString(","))
  }
}

class ItemController @Inject() (cc: ControllerComponents, service: SearchService) extends AbstractController(cc) {
  def show(id: Long): Action[AnyContent] = Action {
    service.find(id).fold(NotFound(s"no item $id"))(Ok(_))
  }

  def file(path: String): Action[AnyContent] = Action(Ok(path))
}

class AdminController @Inject() (cc: ControllerComponents, service: SearchService) extends AbstractController(cc) {
  def reindex(): Action[AnyContent] = Action(Ok(service.reindex()))
}
