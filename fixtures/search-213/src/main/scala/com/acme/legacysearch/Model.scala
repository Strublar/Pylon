package com.acme.legacysearch

final case class Query(text: String, limit: Int)
final case class Hit(id: String, score: Double)

object Hit {
  implicit val byScoreDesc: Ordering[Hit] = Ordering.by[Hit, Double](h => -h.score)
}
