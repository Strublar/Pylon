package gateway

import catalog.{CatalogServiceFs2Grpc, GetItemRequest, Item}
import catalog.shared.CatalogEndpoints
import cats.effect.IO
import com.typesafe.config.Config
import io.grpc.Metadata
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import org.http4s.Uri
import org.http4s.client.Client
import play.api.libs.ws.StandaloneWSClient
import scala.concurrent.{ExecutionContext, Future}
import sttp.client4.*
import sttp.tapir.client.sttp4.SttpClientInterpreter

/** Every way the gateway talks to the catalog service. */
class CatalogClients(
    config: Config,
    backend: SyncBackend,
    http4sClient: Client[IO],
    ws: StandaloneWSClient,
    grpc: CatalogServiceFs2Grpc[IO, Metadata],
    producer: KafkaProducer[String, String]
)(using system: ActorSystem, ec: ExecutionContext):

  private val catalogUrl  = config.getString("catalog.url")
  private val catalogHost = "http://catalog:8080"

  def itemViaSttp(id: Long): String =
    basicRequest.get(uri"$catalogUrl/catalog/items/$id").send(backend).body.fold(identity, identity)

  def itemViaHttp4s(id: Long): IO[String] =
    http4sClient.expect[String](Uri.unsafeFromString(catalogHost) / "catalog" / "items" / id.toString)

  def createViaPekko(name: String): Future[HttpResponse] =
    Http().singleRequest(HttpRequest(HttpMethods.POST, uri = s"$catalogHost/catalog/items", entity = name))

  def itemViaPlayWs(id: Long): Future[Int] =
    ws.url(s"$catalogUrl/catalog/items/$id").get().map(_.status)

  def searchViaTapir(q: String): String =
    SttpClientInterpreter().toRequest(CatalogEndpoints.search, Some(uri"$catalogUrl")).apply(q).send(backend).body.toString

  def itemViaGrpc(id: Long): IO[Item] =
    grpc.getItem(GetItemRequest(id), new Metadata())

  def publishOrder(orderId: String): Unit =
    producer.send(new ProducerRecord[String, String]("orders", orderId, orderId))
