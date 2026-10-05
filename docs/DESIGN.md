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

## Later phases

1. **Endpoints.** Adapters map routes to handler methods, which become the roots of upward
   walks. In order: Play `conf/routes`, Tapir (`endpoint` values and `serverLogic`), http4s
   (`case GET -> Root / ...`), ZIO HTTP, and Akka/Pekko HTTP directives.
2. **Cross-service links.**
   - gRPC: ScalaPB, akka-grpc, fs2-grpc and zio-grpc server implementations are trait
     implementations of the generated service trait, and the client stub calls match exactly.
   - Tapir: endpoints shared by server and client match exactly.
   - HTTP clients (sttp, http4s client, Play WS, Akka HTTP client): matched by HTTP method and path
     template plus the base-URL config key. These links carry a confidence level, and a
     `pylon.yaml` mapping file can override them.
   - Kafka: a separate `publishes` edge kind.
3. **Narrowing forks.** Use Guice `bind(...).to(...)` and constructor call sites
   (`new ProviderA(...)`) to pre-select the implementation that is actually wired.

## Known limits

- The code must compile.
- `Test/compile` runs on the root project. Builds whose root doesn't aggregate every project
  are only partly indexed.
- A val accessed from elsewhere is not a call edge. Its initializer is a node of its own.
- Scala 3 SemanticDB doesn't record for-comprehension desugaring (`flatMap`/`map`). The calls
  inside the comprehension are still recorded.
- Two services that define the same fully qualified symbol share one node. The last one indexed wins.
