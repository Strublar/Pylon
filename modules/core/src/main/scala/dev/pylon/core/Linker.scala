package dev.pylon.core

/**
 * Connects client call sites to the endpoints they reach, across services.
 *
 *  - Exact keys (same Tapir endpoint val, same gRPC method, same Kafka topic): confidence 1.0.
 *  - HTTP: same verb and a matching path pattern (literals agree, parameters and unknown pieces
 *    match anything): 0.8; a client path that is a suffix of the endpoint path (the base URL held a
 *    prefix): 0.5. Endpoints of other services are preferred; the client's own service is a fallback.
 *  - A client hint naming a service (`http://catalog:8080`, `catalog.url`, or a configured rule)
 *    restricts HTTP candidates to that service and adds 0.1.
 */
object Linker {

  /** `hint` substring -> service, from the user's `pylon.json`. */
  final case class Rule(hint: String, service: String)

  def link(remotes: Seq[Remote], services: Seq[String], rules: Seq[Rule] = Nil): Seq[Link] = {
    val servers = remotes.filterNot(_.isClient)
    remotes.filter(_.isClient).flatMap { c =>
      val sameProtocol = servers.filter(_.protocol == c.protocol)
      val byKey        = c.key.toSeq.flatMap(k => sameProtocol.filter(_.key.contains(k)))
      if (byKey.nonEmpty) byKey.map(s => Link(c.symbol, s.symbol, 1.0, keyReason(c)))
      else if (c.protocol == "http") httpLinks(c, sameProtocol, services, rules)
      else Nil
    }.distinctBy(l => (l.client, l.endpoint))
  }

  private def keyReason(c: Remote): String = c.protocol match {
    case "grpc"  => "same gRPC method"
    case "kafka" => "same Kafka topic"
    case _       => "same Tapir endpoint"
  }

  /** The service a hint designates: a configured rule, else an indexed service name found in the hint. */
  def hintedService(hint: Option[String], self: String, services: Seq[String], rules: Seq[Rule]): Option[String] =
    hint.flatMap { h =>
      rules.find(r => h.contains(r.hint)).map(_.service).orElse {
        val lower = h.toLowerCase
        services.filter(s => s != self && containsWord(lower, s.toLowerCase)).sortBy(-_.length).headOption
      }
    }

  private def containsWord(text: String, word: String): Boolean =
    s"(^|[^a-z0-9])${java.util.regex.Pattern.quote(word)}([^a-z0-9]|$$)".r.findFirstIn(text).isDefined

  private def httpLinks(c: Remote, servers: Seq[Remote], services: Seq[String], rules: Seq[Rule]): Seq[Link] = {
    val hinted    = hintedService(c.hint, c.service, services, rules)
    val bonus     = if (hinted.isDefined) 0.1 else 0.0
    val reasonEnd = hinted.fold("")(s => s" · hint → $s")
    val verbOk    = servers.filter(s => s.verb == c.verb || s.verb == "ANY" || c.verb == "ANY")
    val pattern   = segments(c.path)

    def matchesIn(pool: Seq[Remote]): Seq[Link] = {
      val exact = pool.filter(s => samePath(segments(s.path), pattern))
      if (exact.nonEmpty) exact.map(s => Link(c.symbol, s.symbol, 0.8 + bonus, s"HTTP verb + path$reasonEnd"))
      else
        pool
          .filter(s => pattern.nonEmpty && isSuffix(pattern, segments(s.path)))
          .map(s => Link(c.symbol, s.symbol, 0.5 + bonus, s"HTTP path suffix$reasonEnd"))
    }

    val pools = hinted match {
      case Some(svc) => Seq(verbOk.filter(_.service == svc))
      case None      => Seq(verbOk.filter(_.service != c.service), verbOk.filter(_.service == c.service))
    }
    pools.iterator.map(matchesIn).find(_.nonEmpty).getOrElse(Nil)
  }

  /** Path segments; parameters and unknown pieces become `None`. */
  def segments(path: String): Seq[Option[String]] = GraphStore.pathPattern(path.takeWhile(_ != '?'))

  private def segmentMatches(a: Option[String], b: Option[String]): Boolean = (a, b) match {
    case (Some(x), Some(y)) => x == y
    case _                  => true
  }

  def samePath(server: Seq[Option[String]], client: Seq[Option[String]]): Boolean =
    server.size == client.size && server.zip(client).forall { case (a, b) => segmentMatches(a, b) }

  def isSuffix(client: Seq[Option[String]], server: Seq[Option[String]]): Boolean =
    server.size > client.size && client.exists(_.isDefined) && samePath(server.takeRight(client.size), client)
}
