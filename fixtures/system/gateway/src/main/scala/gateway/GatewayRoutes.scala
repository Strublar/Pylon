package gateway

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

class GatewayRoutes(clients: CatalogClients):
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "checkout" / LongVar(id) =>
      for
        item   <- clients.itemViaHttp4s(id)
        viaRpc <- clients.itemViaGrpc(id)
        _      <- IO(clients.publishOrder(s"order-$id"))
        resp   <- Ok(s"$item ${viaRpc.name} ${clients.itemViaSttp(id)}")
      yield resp
    case GET -> Root / "search" :? Q(q) =>
      Ok(clients.searchViaTapir(q))
  }

object Q extends QueryParamDecoderMatcher[String]("q")
