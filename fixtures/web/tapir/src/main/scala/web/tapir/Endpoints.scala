package web.tapir

import cats.effect.IO
import sttp.tapir.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

object Endpoints:
  val base    = endpoint.in("api" / "v1")
  val search  = base.get.in("search").in(query[String]("q")).out(stringBody)
  val item    = base.get.in("items" / path[Long]("id")).out(stringBody)
  val reindex = base.post.in("admin" / "reindex").out(stringBody)

object ItemHandlers:
  def show(service: SearchService, id: Long): String = service.find(id).getOrElse("missing")

class Server(service: SearchService):
  val searchLogic  = Endpoints.search.serverLogicSuccess[IO](q => IO.pure(service.search(q).mkString(",")))
  val itemLogic    = Endpoints.item.serverLogicSuccess[IO](id => IO.pure(ItemHandlers.show(service, id)))
  val reindexLogic = Endpoints.reindex.serverLogicSuccess[IO](_ => IO(service.reindex()))

  val routes = Http4sServerInterpreter[IO]().toRoutes(List(searchLogic, itemLogic, reindexLogic))
