# Pylon design

## Goal

On an unfamiliar codebase, show what runs behind an endpoint or a method, one step at a time.
Every trait call is a fork that the user resolves by choosing an implementation. Choices
form a path the user can revise at any level, and the same graph can be walked upwards,
from callers to entrypoints.

Scope: Scala 2.13 and 3, sbt builds, mostly object-oriented trait code, several services, and
call edges only (no data flow).

## Why SemanticDB

Parsing alone can't tell which `search` `service.search(q)` calls. The compiler can.
With `semanticdbEnabled`, scalac and dotc write `META-INF/semanticdb/**/*.semanticdb`:

- **occurrences:** every identifier resolved to a global symbol (`com/acme/SearchServiceA#search().`)
- **symbol information:** kind, properties (abstract, implicit, given, val), signature,
  class parents, and `overriddenSymbols`
- **synthetics (Scala 2):** compiler-inserted calls (implicit arguments, for-comprehension
  desugaring, `apply`). These are turned on with `-P:semanticdb:synthetics:on`. Scala 3
  reports implicit arguments the same way by default.

scalameta parses the same sources to find the extent of each definition. A reference belongs to
the innermost global `def`/`val`/`var`/`given` whose body contains it. Statements in a class or
object body belong to the class's primary constructor (or the object).

## Compiling without touching the build

`SbtRunner` runs `sbt --addPluginSbtFile=<tmp>/pylon.sbt Test/compile`. That file adds a temp
source directory to the meta-build. The directory holds a `PylonSemanticdbPlugin` auto-plugin that
turns SemanticDB on in every project, and adds the synthetics option only where
`scalaBinaryVersion != "3"` (Scala 3 rejects it). A plain `set` command can't do this, because
the condition depends on each project's own Scala version. Metals and Bloop use the same approach.

## Graph model (SQLite, `.pylon/graph.db`)

| table | content |
|-------|---------|
| `services` | indexed builds |
| `symbols` | types, methods, constructors, vals: kind, display name, signature, file:line, abstract flag. `service IS NULL` marks an external (library) symbol |
| `extends` | child type → parent type |
| `overrides` | method → method it overrides (direct; queries take the transitive closure) |
| `calls` | caller → callee, call site, synthetic flag |

One symbol is one row across services, keyed by SemanticDB symbol. A library indexed as its own
service therefore links to every service that calls it. Re-indexing a service replaces its rows.
Its symbols that other services still call become external again instead of disappearing.

### Queries (`GraphStore`)

- `implementations(m)`: `m` if concrete, plus every concrete transitive overrider.
- `callees(m)`: distinct targets with their call sites. A target with more than one
  implementation is a **fork**, and its candidates are listed.
- `callers(m)`: calls to `m` and to every method `m` overrides, because a call to
  `ProviderTrait.search` may dispatch to `ProviderA.search`. Each caller records `via`, the
  method actually named at the call site.
- `entrypointPaths(m)`: walks callers upwards until it reaches methods with no callers, with
  bounds and cycle protection. Each path is returned root first.

## Viewer (phase 2)

A local server (`pylon map <query>`) serves a JSON API and a React + React Flow page.

- The page state is a path of `{node, chosenImplementation}`, mirrored into the URL.
- **Down mode:** a node box shows the method. Hovering an abstract node lists its
  implementations. Choosing one turns the box into that implementation and loads its callees,
  drawn as arrows labelled with the method name. Library calls are folded away.
- **Breadcrumb:** each step can be reopened and its choice changed, which rebuilds the rest of
  the path.
- **Up mode:** callers (including callers through the trait) and paths to entrypoints.

## Endpoints (phase 3)

An HTTP route is a graph node: `kind = endpoint`, symbol `pylon:endpoint/<service>/<framework>/<VERB> <path>`,
display `GET /api/items/{id}`. Because it has ordinary `calls` edges, every query works unchanged: an endpoint
has no callers, so upward walks and entrypoint paths end there.

Indexing runs in three passes over a service:

1. **Parse** every source once (`ParsedFile`: tree + SemanticDB occurrences by offset).
2. **Find endpoints** (`indexer/endpoints/`). Adapters check that the DSL identifiers resolve, through
   SemanticDB, into the framework's package (`org/http4s/`, `zio/http/`, `sttp/tapir/`, `akka/http/scaladsl/server/`,
   `org/apache/pekko/http/scaladsl/server/`), so a `->` or `path` from another library is never mistaken for a route.
   Each adapter returns `Route`s with a path relative to their *holder* (the def/val that defines them) and `Mount`s:
   - explicit: `Router("/api" -> routes)`, `pathPrefix("admin") { adminRoutes }`, Play `-> /admin admin.Routes`;
   - implicit: any other reference to a holder (`a.routes <+> b.routes`, `toRoutes(List(searchLogic))`), counted
     only when the holder has no explicit mount (so a test referencing routes does not invent a root-level copy).
   `Mounts.resolve` gives every route its full path(s), cycle-safe and bounded.
3. **Attribute calls.** Inline handlers (http4s case bodies, ZIO handlers, Akka/Pekko directive bodies, Tapir
   `serverLogic(...)` expressions) are added as scopes owned by the endpoint; being innermost, they take their calls
   away from the enclosing `val routes`. Play actions are direct `endpoint → controller method` edges. Play's generated
   router and Twirl output (under `target/`) are skipped so they never appear as callers.

The Tapir endpoint val is kept on the route (`key`) for phase 4: a client interpreting the same val in another service
links to it exactly.

## Cross-service links (phase 4)

Each service is indexed on its own; links are recomputed over the whole graph after every `index`, so
the order services are indexed in does not matter.

- **Client nodes** (`kind = client`, `pylon:client/<service>/<protocol>/<file>:<line>:<offset>`) stand for one
  outbound call site and are called by the enclosing method. gRPC/Kafka server sides become endpoints
  (`GRPC pkg.Service/method`, `CONSUME topic`) like HTTP routes.
- **`remotes`** holds, for every endpoint and client: protocol, verb, path template, an exact `key` when one exists
  (Tapir endpoint val, gRPC method, topic) and a `hint` (source text of the client's base URL / config key).
- **`Linker`** (pure, in core) produces **`links`** (client → endpoint, confidence, reason): exact keys 1.0;
  HTTP verb + path 0.8, path suffix 0.5 (base URL with a prefix); a hint naming a service (whole word, or a
  `pylon.json` rule) restricts candidates to it and adds 0.1; other services are preferred to the client's own.
- `GraphStore.callees`/`callers` read links like calls, so walks and entrypoint paths cross services unchanged.

Detection (`indexer/remote/`), package-checked through SemanticDB like endpoints:

- `UrlTemplate` evaluates URL and topic expressions (literals, `s""`/`uri""`, `+`, http4s `/`, vals across files);
  the leading unknown piece or `scheme://host` becomes the hint, the rest a path template with `{}` holes.
- `HttpClients`: sttp, http4s client (incl. inline `Request(...)`), Play WS, Akka/Pekko `HttpRequest`/`RequestBuilding`,
  and Tapir clients (key = endpoint val, path from the shared `Tapir.Shapes` evaluator).
- `Grpc`: generated gRPC files (under `src_managed`, referencing a gRPC runtime) define rpc methods (abstract
  members); a project call to an rpc or stub method becomes a client node *instead of* the generated stub, and calls
  made from generated files (`bindService`) are dropped. Keys are normalised (`…Grpc`, `…Fs2Grpc`, `…Stub`,
  `…Client`, zio `Z…`) so different generators of one `.proto` agree.
- `Kafka`: producer records and subscriptions for kafka-clients, fs2-kafka, zio-kafka and Alpakka/Pekko connectors.

## Later phases

1. **Narrowing forks.** Use Guice `bind(...).to(...)` and constructor call sites
   (`new ProviderA(...)`) to pre-select the implementation that is actually wired.

## Known limits

- The code must compile.
- `Test/compile` runs on the root project. Builds whose root doesn't aggregate every project
  are only partly indexed.
- A val accessed from elsewhere is not a call edge. Its initializer is a node of its own.
- Scala 3 SemanticDB doesn't record for-comprehension desugaring (`flatMap`/`map`). The calls
  inside the comprehension are still recorded.
- Two services that define the same fully qualified symbol share one node. The last one indexed wins.
- Route paths are evaluated statically. Segments built at runtime show as `{?}`, and prefixes applied
  outside the code (reverse proxy, servlet context) are not known.
- Akka/Pekko: a method directive applied *above* a mounted route definition is not carried into it.
- HTTP links are heuristics (shown with their confidence): URLs built far from the call (passed in as a
  parameter, read from a map) evaluate to holes; Kafka topics read from config only link when both sides
  use literal topic names.
- SemanticDB plugin versions: Pylon picks the newest `semanticdb-scalac` published for each project's Scala 2
  version (table in `SbtRunner`); pass `--semanticdb-version` for versions newer than the table.
