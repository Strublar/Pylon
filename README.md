# Pylon

Interactive call-chain maps for Scala codebases.

Point Pylon at one or more sbt builds and ask about an endpoint or a method. It answers
"what actually runs here?" one step at a time. A trait call is a fork: Pylon lists the
implementations, you choose one, and the chain continues from that implementation. You can
also walk upwards, from a method to its callers and on to the entrypoints.

```
method ProviderA.search(query: Query): List[Hit]
  -> SearchServiceA.search(text: String): List[Hit]  {fork: 2 implementations}
       | method CachedSearchServiceA.search(text: String): List[Hit]
       | method ElasticSearchServiceA.search(text: String): List[Hit]
  -> Ranker.rank(hits: List[Hit]): List[Hit]  {fork: 2 implementations}
       | method BoostedRanker.rank(hits: List[Hit]): List[Hit]
       | method DefaultRanker.rank(hits: List[Hit]): List[Hit]
```

The graph comes from the compiler (SemanticDB), so every call site is resolved to the
method it really targets, implicits included. Name matching never decides an edge. Scala 2.13 and Scala 3 are both
supported.

## Status

| Phase | What | State |
|-------|------|-------|
| 1 | Indexer (SemanticDB + scalameta), SQLite graph, CLI queries | done |
| 2 | Web viewer: walk down through forks, walk up to entrypoints | done |
| 3 | Endpoints: Play routes, Tapir, http4s, ZIO HTTP, Akka/Pekko HTTP | done |
| 4 | Cross-service links: gRPC, Tapir shared endpoints, HTTP clients, Kafka | done |
| 5 | Narrowing forks using DI wiring (Guice bindings, constructor sites) | planned |

See [docs/DESIGN.md](docs/DESIGN.md).

## Usage

Requirements: JDK 17+, and npm for the web viewer. sbt is used when installed. Otherwise
`bin/sbt` downloads the launcher from Maven Central. `bin/pylon` builds the viewer and the CLI jar
on first use, and again whenever the sources change.

```bash
# 1. Index one or more services (each is an sbt build). Builds are compiled with SemanticDB
#    turned on through an injected sbt plugin; no build file is modified.
bin/pylon index --service search=fixtures/search-3 --service legacy=fixtures/search-213

# 2. Open the interactive map: on the endpoint list, an endpoint, or a method
bin/pylon serve                             # landing page lists every HTTP endpoint per service
bin/pylon map "GET /api/items/{id}"         # parameter names don't matter: "GET /api/items/42" works too
bin/pylon map ProviderTrait.search          # what does it call?  (--up: who calls it?)
```

HTTP endpoints are entrypoints of the graph:

| Framework | Recognised |
|-----------|------------|
| Play | `conf/routes` and `conf/*.routes` (`:id`, `*path`, `$id<re>`, `->` includes) → controller action |
| Tapir | endpoint values (`endpoint`/base endpoints + `.get`, `.in("a" / path[T]("id"))`) where `serverLogic*` is attached |
| http4s | `case GET -> Root / "a" / LongVar(id) :? Q(q) =>`, mounted with `Router("/api" -> routes)` |
| ZIO HTTP | `Method.GET / "a" / long("id") -> handler {…}` (3.x), `Http.collect { case Method.GET -> !! / "a" => }` (2.x) |
| Akka / Pekko HTTP | `pathPrefix`/`path`/`pathEnd` + method directives, `&`, `concat`/`~`, matchers (`Segment`, `LongNumber`, …), routes defined in other `def`s and mounted under a prefix |

The calls made by a route's handler belong to the endpoint, so walking down from `GET /api/search`
goes straight into the service code, and climbing up from a service method ends at the endpoints
that reach it.

### Across services

Index several services into the same graph and Pylon links the calls between them:

| Transport | Client side | Server side | Match |
|-----------|-------------|-------------|-------|
| HTTP | sttp, http4s client, Play WS, Akka/Pekko HTTP client | any endpoint above | verb + path template (0.8), path suffix (0.5); +0.1 and narrowed when the base URL names a service |
| Tapir | `SttpClientInterpreter().toRequest(endpoint, …)` | the same endpoint value with `serverLogic` | exact |
| gRPC | calls to generated stubs/clients (ScalaPB, fs2-grpc, akka/pekko-grpc, zio-grpc) | implementations of the generated service trait (`GRPC pkg.Service/method`) | exact |
| Kafka | `ProducerRecord(topic, …)`, `Producer.produce(topic, …)` | `subscribe`/`subscribeTo`/`Subscriptions.topics` (`CONSUME topic`) | exact topic |

A client call becomes a box (`→ HTTP GET /catalog/items/{}`) whose dashed arrow leads into the other
service's endpoint, with the match confidence; climbing up from an endpoint shows the calls from other
services. When a base URL does not name its service, tell Pylon in `pylon.json`:

```json
{ "links": [ { "hint": "inventory.url", "service": "catalog" } ] }
```

```bash
bin/pylon index --service gateway=../gateway --service catalog=../catalog
bin/pylon links            # every call to another service and what it reaches
```

In the map:

- **Calls ↓.** Each box is a step of the walk. When the step is a trait method, its box lists the
  implementations. Pick one and the box becomes that implementation, with its calls fanning out to
  the right on arrows labelled with the method name. A `⑂` marks a call into a trait; hover over
  it to jump straight into one of its implementations.
- **Breadcrumb.** Click any earlier step to go back to it. The `⑂` chip on a step's box switches its
  implementation, and the rest of the chain is rebuilt from there. The browser's back button
  undoes the last change, and the URL can be shared.
- **Callers ↑.** Climb from a method to its callers, including callers that go through the trait
  it implements. *Open as call chain* turns the climbed path back into a downward walk, with every
  fork already chosen.
- **Details panel.** Shows the selected method's source, with the lines that call the next step
  highlighted. It also lists implementations and overrides, and *paths from entrypoints*: every
  call chain from an entrypoint (an HTTP endpoint, or a method nobody calls such as `main`) down to the method.

The same queries are available as text:

```bash
bin/pylon query impls ProviderTrait.search            # implementations
bin/pylon query callees ProviderA.search              # what it calls (forks listed; --all adds library calls)
bin/pylon query callers ElasticSearchServiceA.search  # who calls it, incl. through traits
bin/pylon query paths ElasticSearchServiceA.search    # call chains from entrypoints
bin/pylon query callees "GET /api/search"             # what an endpoint's handler calls
bin/pylon endpoints                                   # every endpoint and its handler
```

Symbols can be given as `Type.method`, `pkg.Type.method`, `Type#method`, or a raw SemanticDB
symbol. The graph is stored in `.pylon/graph.db` (`--db` to change). Set `PYLON_SBT` to choose the
sbt executable used to compile indexed builds.

## Development

```bash
bin/sbt test                  # unit tests + indexes the fixtures: Scala 2.13/3, fixtures/web (one project per HTTP
                              # framework) and fixtures/system (two services linked over HTTP, Tapir, gRPC, Kafka)
npm --prefix viewer run dev   # viewer with hot reload, proxying the API of `bin/pylon serve`

# Browser end-to-end check (uses Playwright's Chromium) against the fixtures:
bin/pylon index --service web=fixtures/web --service search=fixtures/search-3 --service legacy=fixtures/search-213 \
  --service catalog=fixtures/system/catalog --service gateway=fixtures/system/gateway
bin/pylon serve --port 7777 &
npm --prefix viewer run e2e
```
