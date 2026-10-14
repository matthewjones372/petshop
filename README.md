# Petshop

A small pet shop, written to see how well a set of Kotlin libraries work
together on a realistic service. It has an HTTP API with an OpenAPI document,
an actor that owns the state, background work written as streams, a
transactional outbox in Postgres, a dependency graph that starts and stops
everything, and tests that run the whole service in-process, under load and as
Given/When/Then stories. The bus between the outbox and its consumer can be
in-process or Kafka carrying Avro.

```bash
docker compose -f demo/docker-compose.yml up -d postgres registry
./gradlew :app:run          # http://127.0.0.1:8080, docs at /api-docs and /reference
./gradlew test             # every test that builds the shop starts its own Postgres and Kafka
./gradlew loadTest         # 200 requests a second at the real graph; not part of test or build
```

`web/` is a SolidJS front end for the shop, in TypeScript: browse pets by species, adopt one, watch `/stats` update,
and follow arrivals and adoptions live on `/live`, which reads the shop's server-sent events at `/events` as an Effect
`Stream` and reconnects on an Effect `Schedule`. Its dev server proxies to the shop on :8080 (`PETSHOP_URL` overrides
that), and Estate's `app` link opens it. The demo's compose also builds and serves it on the same port.

```bash
bun install --cwd web
bun run --cwd web dev       # http://localhost:5173
```

The front end traces itself with Effect's own OTLP tracer. Every call to the shop goes through Effect's `HttpClient`,
so each is a client span that sends `traceparent`, and the shop continues that trace. Adopting a pet is an `adopt pet`
span, each `/live` connection is a `live connection` span that fails when the connection drops, and an uncaught error
is a short span of its own. The dev server proxies `/v1/traces` to Tempo on :4318, which sends
no CORS headers (`TEMPO_URL` overrides the target). To see one trace from a click to the shop, run the demo and the
shop with `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318`, adopt a pet, then open Grafana's Explore at
<http://localhost:3000/explore>, pick **Tempo** and search for service `petshop-web` and span `adopt pet`: the browser's
spans sit above the shop's `POST /pets/{petId}/adoption`, its `adopt` and the chip registry calls. Its log lines go to
the demo's Loki and its Web Vitals to Prometheus the same way, and Estate shows it as the service `petshop-web`, from
Tempo's span metrics; `demo/README.md` says what each number is.

`demo/` adds Prometheus, Grafana, Alertmanager and [Estate](https://github.com/matthewjones372/estate) to watch the
running shop. Grafana is at <http://localhost:3000> and Estate at <http://localhost:8095>; see `demo/README.md`.

[tweet-street](https://github.com/matthewjones372/tweet-street) uses the same libraries on something larger: a bank
in four services, with accounts as actors on a three-node Lark cluster, transfers as sagas, and Proofload and chaos
runs against it. Petshop stays the small one, where each library can be judged on its own.

## Libraries and dependencies

The libraries being tried out:

| Library | What it does here |
|---|---|
| [Pelican](https://github.com/matthewjones372/pelican) | the shop's HTTP contract as values: routes, the OpenAPI document, Swagger UI and a themed Redoc reference, handlers that must answer a declared failure; live and ready probes (`pelican-health`); the chip registry's client generated from its contract; typed WireMock stubs, golden files and a typed test client |
| [Lark](https://github.com/matthewjones372/lark) | the application as a dependency graph that validates, subsets, starts and stops itself (`lark-app`), with its settings and secrets read and reported by `lark-app-typesafe`, the schema migrated by `lark-app-liquibase` before anything reads it, and the graph checked at build time by the wiring plugin; the shop's actor, on virtual threads in a `flock` (`lark-actor`, `lark-app-actor`); the background work, the readiness gauges and the in-process bus as `Stream`s on Lark's Forks (`lark-stream`); Kafka as a `Stream` that commits what it handled and restarts after a defect (`lark-kafka`); a circuit breaker on the chip registry, and retries on schedules; logs, metrics and traces that cross a fork (`lark-slf4j`, `lark-micrometer`, `lark-otel`) |
| [Proofload](https://github.com/matthewjones372/proofload) | load tests that start the whole service in-process, assert correctness under load, and write an HTML report |
| [ExoQuery](https://github.com/ExoQuery/ExoQuery) | every statement against the outbox table, written as Kotlin and checked at compile time |
| [kimney](https://github.com/matthewjones372/kimney) | the mappings between the shop's events and their wire records, and between the domain and the API's DTOs, derived at compile time |

What they run on, and what the tests use:

| | |
|---|---|
| Apache Pekko | HTTP only: Pelican's server and the chip registry's client run on Pekko HTTP |
| PostgreSQL, HikariCP | the `pets` and `outbox` tables |
| Testcontainers | a real Postgres and a real Kafka broker for the tests, one container each per run: a fresh schema per graph, and topics and groups of each test's own |
| WireMock | the chip registry in tests and the demo, stubbed through Pelican's `pelican-test-wiremock` |
| Kotest assertions on JUnit 6 | every test; the end-to-end and app tests are written as stories (below) |
| Arrow | `Either` and `Raise` for declared failures, from the domain up |
| OpenTelemetry, Micrometer, Prometheus, Grafana, Tempo | a span per request, adoption and chip-registry call, exported over OTLP when `OTEL_EXPORTER_OTLP_ENDPOINT` is set; metrics at `/metrics`; the demo's dashboard and traces |
| Logback | where Lark's log lines end up, through `lark-slf4j`, and Pekko's |
| Apache Kafka, avro4k, Confluent's schema registry client | the bus on a broker: events as Avro in the schema registry's wire format, written by avro4k's Confluent serde; tests run the broker in a container and Confluent's in-process `mock://` registry |

## Modules

| Module | What it holds | Uses |
|---|---|---|
| `domain` | `Pet`, `PetShop`, `ChipRegistry`, and the ways adopting can fail | Arrow |
| `registry` | the chip registry's contract as endpoint values, and the client generated from it | Pelican |
| `api` | the endpoints, their failures, the handlers, the DTOs | Pelican, Pekko HTTP, kimney |
| `app` | the actor, the arrivals stream, the chip registry's client, the outbox and its relay, the bus (in process, or Kafka carrying Avro) and its consumer, the wiring, `main` | Lark, kimney |
| `outbox-table` | the outbox's row and its SQL, in an included build on the Kotlin version ExoQuery supports | ExoQuery |
| `loadtest` | the shop under load, started in-process | Proofload |

### The HTTP contract

```kotlin
val adoptPet = endpoint(petId) {
    post("pets" / petId / "adoption")
    summary = "Take a pet home"
    json<PetDto>().orFail(petMissing, petTaken, petNotChipped, unavailable)
}
```

The route, the OpenAPI document at `/openapi.json`, the Swagger page at
`/api-docs` and the Redoc reference at `/reference`, in the shop's own look
(`api/src/main/kotlin/petshop/api/Docs.kt`), are all generated from that. The handler must answer with `ok` or
one of the four declared failures. A `when` over the sealed error type is
exhaustive, and returning a failure the endpoint did not declare does not
compile.

### DTOs and the domain

`Pet` stays in `domain`. The endpoints answer a `PetDto`, and the declared
failures are a sealed `ProblemDto`. [kimney](https://github.com/matthewjones372/kimney)
writes each mapping at compile time:

```kotlin
fun Pet.toDto(): PetDto = transformInto()                // PetId unwrapped, Species by name
fun List<Pet>.toDto(): List<PetDto> = transformInto()
fun PetShopError.toDto(): ProblemDto = into<_, ProblemDto>()
    .withSealedCaseRenamed(RegistryDown::class, ProblemDto.Unavailable::class)
    .withSealedCaseRenamed(NotRecorded::class, ProblemDto.Unavailable::class)
    .transform()                                          // the rest by name
```

`id` is a plain number on the wire. If a species is added to the domain, or a
field is added to a DTO, the mapping fails to compile instead of sending a new
value over the wire.

### Concurrent adoptions

If two people try to adopt the same tortoise at once, only one of them should
get it. An actor handles one message at a time, so the second adopter is told
the pet is taken. The app's own code takes no lock: the actor serialises the
shop's changes, and the relay's claim locks rows in Postgres with
`FOR UPDATE SKIP LOCKED`.

### Arrivals

New pets keep arriving. This is `Stream.tick(...)` into the actor, with the
stream's failure type in its signature: `Stream<Nothing, Pet>` means this one
cannot fail.

### The outbox

Every arrival and every adoption is also recorded as an event, and another part
of the service reads them: a projection that counts arrivals and adoptions by
species and serves the result at `/stats`, and passes each event it has not
seen before to whoever is watching `/events`.

```
Adopt ─▶ shop actor ─▶ BEGIN; UPSERT pets; INSERT INTO outbox; COMMIT ─▶ actor changes      both rows, or neither
                              │
   relay: tick ─▶ BEGIN; SELECT … FOR UPDATE SKIP LOCKED
                   ─▶ publish ─▶ DELETE what the bus took; COMMIT     at least once
                          │
               refused ◀──┴──▶ bus ─▶ projection ─▶ /stats
          (stays, next tick)          dedupe by seq, fold
                                           │
                                           └──▶ feed ─▶ /events   server-sent, live only
```

- **The outbox is a Postgres table, written in one transaction with the
  catalogue.** The actor writes the pet's new state to `pets` and the event's
  row to `outbox` together, and changes its own state only once both have
  committed. So the table never records a sale the catalogue does not hold,
  and the shop never sells a pet without recording the sale. If the write
  throws, the actor's state is
  unchanged and it answers `NotRecorded`, a 503: the pet is still available,
  and the registry is never called. That 503 is the same response as an
  unreachable registry (`Unavailable`, with a message saying which), because
  Pelican allows only one response per status. `seq` is an identity column, so
  it keeps counting across restarts.
- **Every statement is ExoQuery**, apart from the schema, which is a
  SQL-formatted Liquibase changelog (`app/src/main/resources/db/changelog.sql`)
  run by `lark-app-liquibase` as a graph node: the outbox takes its `Migrated`,
  so it cannot be built before the tables exist. The insert is `insert<OutboxRow> { setParams(row).excluding(seq) }.returning { it.seq }`.
  ExoQuery has no locking clause, so the claim is a `@SqlFragment` that wraps
  the query in a free block:
  ```kotlin
  @SqlFragment
  fun <T> forUpdateSkipLocked(rows: SqlQuery<T>): SqlQuery<T> = sql {
      free("$rows FOR UPDATE SKIP LOCKED").asPure<SqlQuery<T>>()
  }
  ```
- **The SQL is built separately, in `outbox-table/`.** ExoQuery's compiler
  plugin is built for Kotlin 2.3.0 and does not load in 2.4: it fails with a
  `ClassCastException` while registering, and Terpal fails the same way. So the
  table, the row and the queries are in an included build on Kotlin 2.3.0,
  which has its own Kotlin Gradle plugin. `app`, on 2.4.20, depends on it as
  `petshop:outbox-table` and converts events to rows and back
  (`PostgresOutbox`). When ExoQuery ships a plugin for Kotlin 2.4,
  `outbox-table/` can move back into `app`.
- **A claim is one transaction.** The relay selects the oldest hundred rows
  `FOR UPDATE SKIP LOCKED`, offers each to the bus while it still holds them,
  deletes the ones the bus took, and commits. Two relays (two instances, or a
  restarted one next to one still finishing) each take the rows the other has
  not locked. Neither waits for the other, and they never publish the same row
  at the same time. If the process dies before the commit, the locks are
  released with the connection and the rows are claimed again.
  `PostgresOutboxSpec` holds one claim open and shows a second one skipping
  past it. The load test runs two whole instances on one table while browsing
  both. Each instance's projection sees only its own relay's bus, so the two
  counts must add up to exactly the number of events recorded. With the
  locking clause removed, they add up to more.
- **The relay is a stream, run on Lark's Forks.** `Stream.tick` makes the
  claim on a virtual thread (`mapPar`, because JDBC blocks), and publishes
  inside it while the rows are held. A refusal is logged and counted, and the
  event stays in the table for the next tick. `restartOnDefect` restarts the
  relay after a claim that throws, and the rolled-back claim loses nothing.
- **The bus** is Lark's `Hub` in the same process, with a bounded queue for
  each reader, standing in for a broker. Like a broker, it refuses when a
  reader is full and once closed.
- **The consumer** is `bus.consume { … }`. It folds each event into one value
  that holds every `seq` seen, because at-least-once delivery means some
  events arrive twice, and the `Tally` of the rest. `/stats` reports how many
  duplicates it dropped.
- **The feed** is a second `Hub`, private to it, that the projection offers
  each first-seen event to; `/events` streams it to each watcher as
  server-sent events. It is fed from the projection rather than the bus
  because a Kafka bus would split its partitions between the two consumers.
  It publishes nothing while nobody watches, and a reconnect starts from now.

The catalogue is the `pets` table. The actor is its only writer and holds it
in memory. On start it reads the table back, and adds the opening catalogue
only for pets that are not there yet, so a restart opens the shop as it was
left, and new arrivals are numbered on from the highest id it holds.
`RestartSpec` adopts a pet, stops the shop, starts it again on the same
database, and finds her adopted. Within one relay, events are published in the order they
were recorded. With more than one relay, each relay's batch is in order and
the batches interleave.

Running it needs a Postgres. `demo/docker-compose.yml` starts one with the
credentials `application.conf` expects, and every test that builds the shop
starts its own through Testcontainers, with a fresh schema per graph.

### The Kafka bus

`KafkaBus` is the same `EventBus` on a real broker, and the projection does not
change. Each event goes out as an Avro record in the schema registry's wire
format, keyed by its `seq` as a number, and comes back through `lark-kafka`'s consumer
loop, which commits an offset only once the projection has folded that event
in.

```
ShopEvent ──kimney──▶ wire record ──avro4k's Confluent serde──▶ [0][schema id][Avro]
                                                                 │
ShopEvent ◀──kimney── wire record ◀──avro4k's Confluent serde────┘
    │                                        (a record that will not read: dead letters, committed past)
    └─▶ projection, on the graph's stream backend ─▶ runCommitting()
```

- **The wire records are separate types.** `petshop.app.wire` holds
  `@Serializable` classes whose schema avro4k derives, committed as
  `golden/shop-event.avsc`. The domain's `ShopEvent` knows nothing about Avro.
  kimney derives both mappings at compile time: `ShopEvent.toWire()` and
  `WireEvent.toDomain()` are one `transformInto()` each, and a field that
  either side cannot fill does not compile.
- **avro4k writes the registry's format itself.** Its Confluent serde encodes
  the wire record straight to Avro and registers its schema, with no
  `GenericRecord` between them. The serde is marked experimental, and
  `Avro.kt` opts in to it.
- **One schema per topic.** The three events are a union inside one
  `ShopEventRecord`, so the registry holds versions of one subject instead of
  a record type per case.
- **Publishing uses `lark-kafka`'s `Producer`.** A record it gives up on is a
  `BusRefused`, and the event stays in the outbox. The time a send may take is
  set by the producer's own timeouts, without a `get()` around the send.
- **Two kinds of failure are handled differently.** A record that is not the
  shop's Avro is a `DecodeError`: it is written unchanged to
  `<topic>.dead-letters` and its offset is committed. A registry that cannot be
  reached is a defect, and the run ends so its owner can restart it. Confluent
  throws the same exception for both, so `registryDown` inspects the cause it
  wraps.

`petshop.bus.kind` (`BUS` in the environment) picks the bus: `in-process` by
default, or `kafka`, which reads the broker, topic, group and registry from
`petshop.bus.kafka`. The choice is made where the graph is assembled, with
lark-app's `Config.choosing`, so the unused branch has no node: a shop on the
in-process bus opens no producer, and a shop on Kafka starts no hub. Any other
`kind` stops the application from starting, with a message naming where it was
set.

Each bus has its own tests. `EventBusContract` holds what any `EventBus` must
do: an event published before anyone reads reaches the first reader, a reader
reads events in the order they were published, and the whole service runs on
it, with an adoption reaching the projection. `HubBusSpec` and `KafkaBusSpec`
each extend it, say only how to make their bus, and add tests for what is true
of that bus alone. Adding another bus means writing one class that passes it.

### Wiring the application

```kotlin
fun petshopFrom(conf: Config): Module =
    configOf(conf) + settings + telemetry + database + http + registry + theShop + checks + meters + arrivals +
        events + bus(conf) + web

object Petshop : LarkApp<PelicanServer>() {
    override val module: Module = petshop
    override fun AppScope.run(root: PelicanServer) {
        logInfo(reported())
        awaitShutdown()
    }
}
```

`main` is one line. The root is a value that can be read without running
anything. `run` logs where every setting came from, secrets masked, and returns
when the process is asked to stop; the graph's release then stops the server,
the streams and the pool in reverse order. The compiler plugin checks the graph against it as you type, and
`larkWiring` checks it by running it.

The load test starts the same value in its own process, runs two thousand
requests through it, and gets the port back afterwards.

### The chip registry

An adoption is recorded with the national chip registry: a `GET` for the pet's
chip, then a `POST` naming its new keeper. The actor decides who gets the pet
first, so the people who lose a race never reach the registry, and if the
registry cannot record the keeper the pet goes back to being available.

The registry's contract is written the same way as the shop's own, as values,
in `registry`. Pelican's Gradle plugin generates the client from them; the
generated code is committed and checked on every build. `HttpChipRegistry`
holds the rest: the shop's decision about which answers mean "no chip" and
which mean "could not ask", and a lark circuit breaker, so that after five
failures in a row an adoption is refused at once instead of after the timeout.

## Tests

Every test in `app` is written as a story: `Given`, `When`, `Then`, `And` and
`But` each run a block, time it and return its value, so the next step can
check what the last one did. A failing step ends the story with an
`AssertionError` whose message is the story up to that step, so CI and the
JUnit XML show where it broke. The console output is coloured under
`FORCE_COLOR` or in IntelliJ. The code is Lark's
`lark-test` module.

The end-to-end test uses the whole graph that `main` starts, with three nodes
swapped: the registry's location, the database the outbox is in, and the bus,
which is Kafka. The test calls the shop through its own endpoints, so it has no
URLs, status codes or JSON, and it reads the Postgres table and the Kafka topic
as well as the API:

```kotlin
@Test
fun `somebody adopts a tortoise, and every part of the service hears about it`() = story {
    Given("a chip registry that knows every pet but Mrs Peel") {
        registry.stub(lookupChip, 3L) answers noSuchChip(Problem("never chipped"))
    }
    theService.use { server: PelicanServer ->
        apiClient(server.baseUrl, JacksonCodecs).use { shop ->
            val nibbles = When("Ada adopts Nibbles") { shop.outcome(adoptPet, 1L) }
            Then("Nibbles is hers") { nibbles.shouldBeOk().adopted shouldBe true }

            val peel = When("somebody asks for Mrs Peel") { shop.outcome(adoptPet, 3L) }
            Then("she has no chip on record") { peel.shouldBeError() shouldBe NotChipped(3) }

            And("the outbox drains").eventually(5.seconds) { database.unsent() shouldBe 0L }
            And("/stats counts every event the table recorded").eventually(5.seconds) {
                shop.call(stats, Unit).events.toLong() shouldBe database.recorded()
            }

            val onTheTopic = When("the topic is read as the broker holds it") {
                kafka.records(TOPIC, LongDeserializer(), ShopEventDeserializer(schemas))
            }
            Then("every event the outbox recorded is on it once, keyed by its seq") {
                onTheTopic.map { it.key() }.sorted() shouldBe (1..database.recorded()).toList()
            }
            And("the projection committed every event").eventually(5.seconds) {
                kafka.committed(GROUP, TOPIC) shouldBe database.recorded()
            }
        }
    }.shouldBeRight()
}
```

```
Story: somebody adopts a tortoise, and every part of the service hears about it
  ✓ Given a chip registry that knows every pet but Mrs Peel      1 ms
  ✓ When Ada adopts Nibbles                                      153 ms
  ✓ Then Nibbles is hers                                         0 ms
  ...
  ✓ And the outbox drains                                        48 ms, 2 tries
```

`use` releases the port, the actor system and the pool when the block returns,
so there is no teardown to write. `eventually` blocks instead of suspending,
because `use`'s block is not `suspend`, and its timeout uses Lark's clock.

A test of one part starts only that part. `subgraph` cuts the graph down to
what a node depends on, and a collaborator is replaced in one of two places,
depending on what the test is about:

```kotlin
// about the shop: swap the node. `overriding` refuses a key the graph does not hold,
// so a fake bound under the wrong type cannot leave the real client running beside it.
petshop.overriding(single<ChipRegistry> { FakeRegistry() }).subgraph<PetShop>()

// about the client: keep the node, swap the server. The stubs are the registry's own
// endpoints, so they move with its contract; the answers are values it declares.
registry.stub(lookupChip, 1L) answers ok(ChipRecord("981000000000001", keeper = "Petshop"))
registry.stub(recordKeeper, In2("981000000000001", NewKeeper("Ada"))) fails Fault.CONNECTION_RESET_BY_PEER
```

The relay's tests run it on a clock the test controls (`lark-stream-test`), so
an hour of ticks is one `adjust` call and nothing sleeps. The API the shop
offers its callers is recorded in golden files: `golden.operations(api.spec())`
fails on a change that would break an existing caller.

The load test checks correctness under load, through the same typed client:

```kotlin
// adopt the tortoise, then have two hundred a second try to adopt it again.
// every one must be told it is gone: an Ok in there is two people sold one pet.
exec(adoptTaken) { step ->
    when (val answer = client.outcome(adoptPet, 1L)) {
        is Outcome.Ok -> step.fail("the tortoise was sold twice")
        is Outcome.Err -> if (answer.error !is AlreadyAdopted) step.fail("not the declared failure")
    }
}
```

## Assessment of each library

| | Verdict |
|---|---|
| Pelican | Worth it. The most benefit for the least effort: one contract gives the routes, the documents, the probes, a client and its stubs |
| Lark, the application graph | Worth it for a service with several resources and tests that start parts of it. It does not reduce the amount of code; it rules out a set of mistakes |
| Lark, configuration | Worth it. Every fault in a file at once, secrets that cannot be printed, and a report of where each setting came from |
| Lark, streams | Worth it for background work: the relay, the arrivals, the projection and the readiness gauges are one shape, testable on a clock the test controls, and restart themselves |
| Lark, resilience | Worth it. A circuit breaker and retries are a few lines, and report their own metrics |
| Lark, Kafka | Worth it. A topic is a stream like the others, an offset is committed only once its event is handled, and a registry outage pauses the consumer instead of ending it |
| Proofload | Worth it. The least effort of anything here: correctness under load in a few lines |
| kimney | Worth it once events or the API have a wire format of their own; a missing field is a compile error |
| ExoQuery | Not here. The type checking is real, but for six statements it costs a separate build on an older Kotlin |

### Pelican

One contract value gives the routes, the OpenAPI document, Swagger UI, a Redoc
reference, the registry's client, its stubs and a typed test client.

**What it gives.**

- **Failures are types.** Every failure an endpoint declares is in its
  handler's type, so the `when` over the shop's errors is exhaustive and a new
  error is a build failure, not a 500.
- **The documents cannot drift.** The OpenAPI document is the only description
  of the API; nothing is written in YAML. Swagger UI at `/api-docs` and a Redoc
  reference at `/reference`, themed with Pelican's `reference { }` look, read it.
- **Probes from checks.** `pelican-health` serves `/health/live` and
  `/health/ready` in `application/health+json` from the checks the shop
  declares: the database and the actor are critical, the background runs are
  warnings.
- **The registry's client is generated** from its contract and checked on every
  build. Its tests stub it through the same endpoint values, so a contract
  change moves the stubs or stops them compiling.
- **Tests call by endpoint.** The end-to-end and load tests use a typed client,
  with no URLs or status codes, and a failure arrives as the declared value.
- **Golden files** record what callers were promised, and fail only on a change
  that would break them.

**What it costs.** Two things to look up once: `errorJson` for a declared
failure, and the import for `orFail`. Redoc's theme is Redoc's own JSON, passed
through as it is.

**Its limits.** Two failures can share a status only when each is `tagged`; the
shop does not tag its 503s, so `NotRecorded` and an unreachable registry are
told apart by the message. A declared status whose body does not match the
declared shape is not that failure: the generated client throws
`ApiCallFailed`, which the shop treats as the registry being unreachable.

### Lark: the application graph

**What it gives.** Each of these has a test in this repository:

- **A pet is sold once.** Twenty adopters race for one tortoise on a subgraph
  that binds no port, and exactly one wins. Under load, two hundred requests a
  second try to adopt a pet that is already gone, and every one is told so.
- **An actor cannot answer with null.** `Reply<A : Any>` does not accept a
  nullable type, so "no such pet" is an `Option<Pet>`.
- **The wiring is checked at build time.** A missing key is a compiler error on
  the recipe that asked for it, and `larkWiring` runs every graph on `check`.
- **Fakes must replace something.** `overriding` refuses a node under a key the
  graph does not hold.
- **Nothing is left open.** The port, the streams, the actor system, the pool
  and the SDK are released in reverse dependency order, which is what lets the
  load test start the whole application twice in one process. `awaitShutdown`
  makes a SIGTERM do the same.
- **Metrics are plain calls.** `counter("petshop.adoptions").increment()` with
  the outcome as a label answers both how many adoptions and how many refusals.
- **Log lines can be searched by pet.** `adopt` annotates `pet_id` and
  `adopted_by`; the pair survives the fork the ask runs on and reaches
  Logback's MDC through `lark-slf4j`.

**What it costs.** `Wiring.kt` holds a graph a hand-written `main` might wire in
fewer lines. `singleOf(::Thing)` shortens a plain constructor, but most nodes
here are a resource with a release, an actor, a stream or a config section.

**Its limits.** The compiler plugin behind the editor errors uses Kotlin's
compiler internals, which have no stability promise, so it is the part most
likely to break on a Kotlin upgrade. It only reads graphs from source it can
see: IntelliJ runs it only with `kotlin.k2.only.bundled.compiler.plugins.enabled`
unchecked, a module from another Gradle module has no source to read, and an
incremental compile can skip a graph spread over several files. It says when it
has not read a graph, and `larkWiring`, which runs the graph, is not affected.

### Lark: configuration

**What it gives.** Each module reads its own section with `config(path) { }`,
and a bad file reports every fault at once, in Typesafe Config's words with the
file and the line. The bus is chosen with `choosing`, so the branch not taken
starts nothing. The database password is a `Secret`: it prints as a mask in
`toString`, a log line or an exception, and gives its value only to `reveal()`.
At start the shop logs each setting beside the file or environment variable it
came from, with the password and the database URL masked. A test changes one
key with `overridingConfig` and keeps the rest of `application.conf`.

**Its limits.** `layeredConfig().origins()`, which would also say what each
value overrode, throws on the `key = default` / `key = ${?ENV}` pair
`application.conf` uses, so the report comes from the merged document.

### Lark: streams

**What it gives.**

- **One shape for background work.** The relay, the arrivals, the projection
  and the readiness gauges are each a `Stream` started on the graph's
  `StreamBackend`, and their `Running` is the node's release. A relay,
  projection or arrivals run that ends is a failing check.
- **Blocking calls are easy to place.** `mapPar` runs the relay's JDBC claim on
  a virtual thread.
- **Refusals are values.** An event the bus turns away is logged, counted and
  left in the outbox for the next tick, and the stream's failure type stays
  `Nothing`.
- **Defects restart the stream.** `restartOnDefect` runs the relay again after a
  claim that throws, and the rolled-back claim loses nothing.
- **The in-process bus is a library type.** `Hub` sends what one publisher
  publishes to every reader, keeps what was published before the first reader,
  and refuses instead of blocking when a reader is full.
- **Time is the test's.** The relay's tests run the same description an hour at
  a time on a `TestClock`, and `eventually` waits without `suspend` or a sleep.

**Its limits.** Forks' `stop` interrupts the loop, which should cut short a
claim blocked on Postgres and roll it back; no test here checks that.

### Lark: resilience

**What it gives.** The chip registry is called through a `policy` with a
`CircuitBreaker`: after five failures in a row the shop stops asking and refuses
adoptions at once, then lets one trial through on an exponential schedule. A
declared 404 is an answer, not a failure, so it never opens the breaker.
`lark.breaker.state` and `lark.policy.calls` are on `/metrics` without any code
of the shop's. The same `Schedule` values drive the relay's and the Kafka
consumer's restarts.

**Its limits.** No retry: `lookupChip` is a `GET` and could be retried, but
`recordKeeper` is a `POST` the registry gives no idempotency key for.

### Lark: Kafka

**What it gives.** Kafka is a `Stream` like any other. `runCommitting()` commits
an offset only once its record has reached the end of the stream, so the
projection never commits an event it has not folded in. A record that will not
decode is a `Left` routed to dead letters with its origin in the headers,
instead of stopping the consumer. A schema registry that is down is a transient
defect, and `restartOnDefect` reopens the consumer from the last commit on a
backoff capped at 30 seconds. The producer's failure is a value,
`PublishFailed`, which the shop turns into the `BusRefused` the relay handles.

**What it costs.** Confluent's serializer is not on Maven Central, so the build
adds Confluent's repository, limited to `io.confluent`. avro4k's serde needs
Confluent 8.3, which asks for its own `8.3.0-ccs` build of the Kafka 4.3
client, so the build pins Apache's `4.3.0` to keep one client on the classpath.
`lark-kafka` is built against 3.8 and runs on it; `KafkaBusSpec` is what says
so.

**Its limits.** Delivery is at least once. `lark-kafka` has `transacted` for a
consumer that writes back to Kafka, but the projection folds into memory, so it
dedupes by `seq` instead.

### Proofload

Three load tests, each a scenario, an assertion and an HTML report, against the
whole service started in-process:

- browsing at two hundred requests a second, after two seconds of unrecorded
  warm-up, with the shop's own service time held under 100 ms at p99;
- the rush on a pet already adopted, which checks correctness;
- two instances on one outbox table, whose two relays must publish every event
  exactly once between them.

The report says whether the load generator kept to its own schedule, which
tells you whether a p99 reflects the shop or the tool, so the test asserts the
shop's service time rather than the response time. The load tests run as
`./gradlew loadTest`, apart from `test` and `build`, because measurements want a
quiet machine.

### kimney

**What it gives.** The domain events and their wire records are separate types,
and so are the API's DTOs ([`api/.../Dtos.kt`](api/src/main/kotlin/petshop/api/Dtos.kt)),
and every mapping between them is derived at compile time. A field one side
cannot fill, a species the DTO does not have, or a failure added to
`PetShopError` without a place in `ProblemDto` is a compile error on the call
that uses it, and the message names the fix. The errors show in IntelliJ with
the same registry setting Lark's need.

**What it costs.** A compiler plugin in `api`'s and `app`'s builds that stops
the build on any Kotlin minor version it does not support yet.

### ExoQuery

**What it gives.** Every statement against the outbox except the schema, which
is a Liquibase changelog, is Kotlin, checked when it compiles. The locking clause, which it has no syntax
for, is a `@SqlFragment` around a `free` block.

**What it costs.** Its compiler plugin is built for Kotlin 2.3.0 and does not
load on the project's 2.4, so the table, the row and the queries live in an
included build on 2.3.0, and `app` converts events to rows and back. For six
statements that costs more than the SQL it replaces. Worth another look when it
loads on the project's Kotlin, or for a project with many more queries.

## Versions

Pelican `1.0.0`, Lark `0.10.0` (its Gradle wiring plugin `0.10.0`), Proofload
`0.1.0-rc4`, ExoQuery `2.0.4.PL`, kimney `0.3.0`. Pekko `1.2.1`, Pekko HTTP
`1.3.0`, Arrow `2.1.2`, Testcontainers `2.0.5`, PostgreSQL driver `42.7.13`,
HikariCP `7.1.0`, OpenTelemetry SDK `1.62.0`, Micrometer's Prometheus registry `1.16.6`, Logback `1.5.34`, Kotest `6.2.4`, JUnit `6.1.3`,
avro4k `2.12.0`, Confluent's Avro serializer `8.3.0`, Kafka client `4.3.0`, the
`apache/kafka:4.3.1` image for tests and the demo, and
`confluentinc/cp-schema-registry:8.3.2` in the demo. Kotlin 2.4.20 (2.3.0 for
`outbox-table/`), JDK 25 (21 for `outbox-table/`).
