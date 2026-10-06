package com.acme.search

class ElasticSearchServiceA(client: HttpClient) extends SearchServiceA:
  def search(text: String): List[Hit] =
    client.get(s"/_search?q=$text").map(line => Hit(line, 1.0))

class CachedSearchServiceA(underlying: SearchServiceA, cache: Cache) extends SearchServiceA:
  def search(text: String): List[Hit] =
    cache.getOrElseUpdate(text)(underlying.search(text))
