package com.acme.search

class DefaultRanker extends Ranker:
  // `sorted` resolves the given Hit.byScoreDesc
  def rank(hits: List[Hit]): List[Hit] = hits.sorted

class BoostedRanker(boost: Double) extends DefaultRanker:
  override def rank(hits: List[Hit]): List[Hit] =
    super.rank(hits.map(h => h.copy(score = h.score * boost)))
