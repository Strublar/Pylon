package catalog.app

import cats.effect.IO
import fs2.kafka.{ConsumerSettings, KafkaConsumer => Fs2Consumer}
import java.time.Duration
import org.apache.kafka.clients.consumer.KafkaConsumer

class OrderConsumer(consumer: KafkaConsumer[String, String], repo: ItemRepository):
  def run(): Unit =
    consumer.subscribe(java.util.List.of("orders"))
    consumer.poll(Duration.ofSeconds(1)).forEach(record => repo.reserve(record.value()))

class Fs2OrderConsumer(settings: ConsumerSettings[IO, String, String], repo: ItemRepository):
  def stream: fs2.Stream[IO, Unit] =
    Fs2Consumer.stream(settings).subscribeTo("orders").records.evalMap(c => IO(repo.reserve(c.record.value)))
