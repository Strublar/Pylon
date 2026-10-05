package com.acme.search

class ProviderA(service: SearchServiceA, ranker: Ranker) extends ProviderTrait:
  def name: String = "A"

  def search(query: Query): List[Hit] =
    val raw = service.search(query.normalized.text)
    ranker.rank(raw).take(query.limit)

class ProviderB(index: InMemoryIndex) extends ProviderTrait:
  def name: String = "B"

  def search(query: Query): List[Hit] =
    for
      term <- Tokenizer.tokens(query.text)
      hit  <- index.lookup(term)
    yield hit
