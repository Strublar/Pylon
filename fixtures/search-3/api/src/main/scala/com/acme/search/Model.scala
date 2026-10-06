package com.acme.search

final case class Query(text: String, limit: Int)
final case class Hit(id: String, score: Double)

object Hit:
  given byScoreDesc: Ordering[Hit] = Ordering.by[Hit, Double](h => -h.score)

extension (q: Query)
  def normalized: Query = q.copy(text = q.text.trim.toLowerCase)
