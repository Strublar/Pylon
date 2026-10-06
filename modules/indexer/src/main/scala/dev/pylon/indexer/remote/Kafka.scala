package dev.pylon.indexer.remote

import dev.pylon.indexer.endpoints.{ParsedFile, Trees}
import scala.meta._

/**
 * Kafka topics. Producing (`new ProducerRecord(topic, …)` from kafka-clients, also used by
 * Alpakka/Pekko connectors; fs2-kafka `ProducerRecord(topic, …)`; zio-kafka `Producer.produce(topic, …)`)
 * is a client call; subscribing (`consumer.subscribe(...)`, fs2-kafka `subscribeTo(...)`,
 * `Subscriptions.topics(...)`, zio `Subscription.topics(...)`) makes a `CONSUME topic` endpoint that
 * leads to the method doing the subscription.
 */
object Kafka {

  private val ProducerRecords = Seq(
    "org/apache/kafka/clients/producer/ProducerRecord#",
    "fs2/kafka/ProducerRecord.",
    "zio/kafka/producer/Producer"
  )
  private val Subscriptions = Seq(
    "org/apache/kafka/clients/consumer/KafkaConsumer#subscribe",
    "org/apache/kafka/clients/consumer/Consumer#subscribe",
    "fs2/kafka/KafkaConsume#subscribeTo",
    "fs2/kafka/KafkaConsumer",
    "akka/kafka/Subscriptions.topics",
    "org/apache/pekko/kafka/Subscriptions.topics",
    "zio/kafka/consumer/Subscription.topics"
  )

  def scan(f: ParsedFile, vals: UrlTemplate.Vals): RemoteSites = {
    val clients = f.tree.collect {
      // new ProducerRecord[K, V](topic, ...)
      case n: Term.New if isProducerRecord(f, n.init.tpe) =>
        n.init.argClauses.headOption.flatMap(_.values.headOption).toSeq.flatMap(topic => publish(f, n, topic, vals, "kafka-clients"))
      // ProducerRecord(topic, k, v), Producer.produce(topic, ...)
      case a: Term.Apply if Trees.lastName(a.fun).exists(n =>
            (n.value == "ProducerRecord" || n.value == "apply" || n.value.startsWith("produce")) &&
              f.symbols(n).exists(s => ProducerRecords.exists(s.startsWith))) =>
        a.argClause.values.headOption.toSeq.flatMap(topic => publish(f, a, topic, vals, library(f, a.fun)))
    }.flatten

    val servers = f.tree.collect {
      case a: Term.Apply if Trees.lastName(a.fun).exists(n =>
            Set("subscribe", "subscribeTo", "topics")(n.value) && f.symbols(n).exists(s => Subscriptions.exists(s.startsWith))) =>
        val handler = f.holderOf(a).toSeq
        a.argClause.values.flatMap(UrlTemplate.strings(f, _, vals)).collect { case Left(topic) => topic }.map { topic =>
          ServerSite("kafka", "CONSUME", topic, Some(topic), library(f, a.fun), f.uri, a.pos.startLine + 1, a.pos.endLine + 1, handler)
        }
    }.flatten

    RemoteSites(clients, servers)
  }

  private def isProducerRecord(f: ParsedFile, tpe: Type): Boolean = tpe match {
    case a: Type.Apply                => isProducerRecord(f, a.tpe)
    case n: Type.Name                 => f.symbols(n).exists(s => ProducerRecords.exists(s.startsWith))
    case s: Type.Select               => f.symbols(s.name).exists(s => ProducerRecords.exists(s.startsWith))
    case _                            => false
  }

  private def publish(f: ParsedFile, at: Tree, topic: Term, vals: UrlTemplate.Vals, lib: String): Seq[ClientSite] =
    UrlTemplate.strings(f, topic, vals).map {
      case Left(name)    => ClientSite("kafka", "PUBLISH", name, Some(name), None, lib, f.uri, at.pos.startLine + 1, at.pos.start)
      case Right(source) => ClientSite("kafka", "PUBLISH", "{}", None, Some(source), lib, f.uri, at.pos.startLine + 1, at.pos.start)
    }

  private def library(f: ParsedFile, fun: Term): String =
    Trees.lastName(fun).flatMap(n => f.symbols(n).headOption).map {
      case s if s.startsWith("fs2/")   => "fs2-kafka"
      case s if s.startsWith("zio/")   => "zio-kafka"
      case s if s.startsWith("akka/")  => "alpakka-kafka"
      case s if s.contains("pekko/")   => "pekko-connectors-kafka"
      case _                           => "kafka-clients"
    }.getOrElse("kafka")
}
