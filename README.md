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
| 2 | Web viewer: walk down through forks, walk up to entrypoints | next |
| 3 | Endpoints: Play routes, Tapir, http4s, ZIO HTTP, Akka/Pekko HTTP | planned |
| 4 | Cross-service links: gRPC, Tapir shared endpoints, HTTP clients, Kafka | planned |
| 5 | Narrowing forks using DI wiring (Guice bindings, constructor sites) | planned |

See [docs/DESIGN.md](docs/DESIGN.md).

## Usage

Requirements: JDK 17+ and sbt. If sbt isn't installed, `bin/sbt` downloads the launcher from
Maven Central.

```bash
# Index one or more services (each is an sbt build). Builds are compiled with SemanticDB
# turned on through an injected plugin; no build file is modified.
bin/sbt "cli/run index --service search=fixtures/search-3 --service legacy=fixtures/search-213"

# Query the graph (stored in .pylon/graph.db)
bin/sbt "cli/run query impls ProviderTrait.search"        # implementations
bin/sbt "cli/run query callees ProviderA.search"          # what it calls (forks listed)
bin/sbt "cli/run query callers ElasticSearchServiceA.search"  # who calls it, incl. through traits
bin/sbt "cli/run query paths ElasticSearchServiceA.search"    # call chains from entrypoints
```

Symbols can be given as `Type.method`, `pkg.Type.method`, `Type#method`, or a raw SemanticDB
symbol. `--all` includes library calls in `callees`. Set `PYLON_SBT` to choose the sbt
executable used to compile the indexed builds (default: `sbt` on the PATH).

## Development

```bash
bin/sbt test   # unit tests + indexes both fixtures (fixtures/search-213, fixtures/search-3)
```
