package web.http4s

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.server.Router

object QueryParam extends QueryParamDecoderMatcher[String]("q")

class SearchRoutes(service: SearchService):
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "search" :? QueryParam(q) =>
      Ok(service.search(q).mkString(","))
    case GET -> Root / "items" / LongVar(id) =>
      service.find(id).fold(NotFound())(Ok(_))
    case req @ DELETE -> Root / "items" / LongVar(id) =>
      Ok(service.delete(id))
  }

object HealthRoutes:
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root => Ok("ok") }

object Server:
  def app(service: SearchService): HttpRoutes[IO] =
    Router("/api" -> SearchRoutes(service).routes, "/health" -> HealthRoutes.routes)
