package dev.pylon.indexer.remote

/**
 * An outbound call site found in a source file. It becomes a `client` node called by the method
 * enclosing `offset`, with a `Remote` row for linking.
 *
 * @param replaces when set, the call edge to this symbol at the same site is replaced by the client node
 *                 (gRPC stubs: the generated stub method is noise once the remote call is known)
 */
final case class ClientSite(
    protocol: String,
    verb: String,
    path: String,
    key: Option[String],
    hint: Option[String],
    library: String,
    file: String,
    line: Int,
    offset: Int,
    replaces: Option[String] = None
) {
  def label: String = protocol match {
    case "http"  => s"→ HTTP $verb $path"
    case "grpc"  => s"→ gRPC $path"
    case "kafka" => s"→ Kafka publish $path"
    case other   => s"→ $other $path"
  }
}

/** A served endpoint that is not an HTTP route: a gRPC method implementation or a Kafka subscription. */
final case class ServerSite(
    protocol: String,
    verb: String,
    path: String,
    key: Option[String],
    library: String,
    file: String,
    line: Int,
    endLine: Int,
    targets: Seq[String]
) {
  def display: String = s"$verb $path"
}

final case class RemoteSites(clients: Seq[ClientSite] = Nil, servers: Seq[ServerSite] = Nil) {
  def ++(o: RemoteSites): RemoteSites = RemoteSites(clients ++ o.clients, servers ++ o.servers)
}
