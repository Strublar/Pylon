package catalog.app

import cats.effect.IO
import catalog.shared.CatalogEndpoints
import org.http4s.*
import org.http4s.dsl.io.*

class ItemRoutes(repo: ItemRepository):
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "catalog" / "items" / LongVar(id) =>
      repo.find(id).fold(NotFound())(Ok(_))
    case req @ POST -> Root / "catalog" / "items" =>
      req.as[String].flatMap(name => Ok(repo.save(name).toString))
  }

class SearchServer(repo: ItemRepository):
  val searchLogic = CatalogEndpoints.search.serverLogicSuccess[IO](q => IO.pure(repo.search(q).mkString(",")))
