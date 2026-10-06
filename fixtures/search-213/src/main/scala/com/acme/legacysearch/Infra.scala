package com.acme.legacysearch

import scala.collection.mutable

trait HttpClient {
  def get(path: String): List[String]
}

class FakeHttpClient extends HttpClient {
  def get(path: String): List[String] = List(path)
}

class Cache {
  private val store = mutable.Map.empty[String, List[Hit]]

  def getOrElseUpdate(key: String)(compute: => List[Hit]): List[Hit] =
    store.getOrElseUpdate(key, compute)
}

class InMemoryIndex(docs: Map[String, List[Hit]]) {
  def lookup(term: String): List[Hit] = docs.getOrElse(term, Nil)
}

object Tokenizer {
  def tokens(text: String): List[String] = text.split(" ").toList.filter(_.nonEmpty)
}
