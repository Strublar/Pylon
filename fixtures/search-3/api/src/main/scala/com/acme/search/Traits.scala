package com.acme.search

trait ProviderTrait:
  def name: String
  def search(query: Query): List[Hit]

trait SearchServiceA:
  def search(text: String): List[Hit]

trait Ranker:
  def rank(hits: List[Hit]): List[Hit]

trait HttpClient:
  def get(path: String): List[String]
