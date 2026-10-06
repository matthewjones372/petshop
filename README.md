# Petshop

A small pet shop, written to see how well a set of Kotlin libraries work
together on a realistic service. It has an HTTP API with an OpenAPI document,
an actor that owns the state, background work written as streams, a
transactional outbox in Postgres, a dependency graph that starts and stops
everything, and tests that run the whole service in-process, under load and as
Given/When/Then stories. The bus between the outbox and its consumer can be
in-process or Kafka carrying Avro.

The assessment further down describes the libraries and this repository as
they are now.

```bash
docker compose -f demo/docker-compose.yml up -d postgres registry
./gradlew :app:run          # http://127.0.0.1:8080, docs at /api-docs
./gradlew test             # every test that builds the shop starts its own Postgres and Kafka
./gradlew loadTest         # 200 requests a second at the real graph; not part of test or build
```

`demo/` adds Prometheus, Grafana, Alertmanager and [Estate](https://github.com/matthewjones372/estate) to watch the
running shop. Grafana is at <http://localhost:3000> and Estate at <http://localhost:8095>; see `demo/README.md`.

## Libraries and dependencies

The libraries being tried out:

| Library | What it does here |
|---|---|
| [Pelican](https://github.com/matthewjones372/pelican) | the shop's HTTP contract as values: routes, the OpenAPI document and Swagger page, handlers that must answer a declared failure; the chip registry's client generated from its contract; typed WireMock stubs, golden files and a typed test client |
| [Lark](https://github.com/matthewjones372/lark) | the application as a dependency graph that validates, subsets, starts and stops itself (`lark-app`, with its Typesafe Config and Gradle wiring-check modules); the shop's actor, on virtual threads in a `flock` (`lark-actor`, `lark-app-actor`); the background work and the in-process bus as `Stream`s on Lark's Forks (`lark-stream`); Kafka as a `Stream` that commits what it handled (`lark-kafka`); retries on schedules; logs, metrics and traces that cross a fork (`lark-slf4j`, `lark-micrometer`, `lark-otel`) |
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
| OpenTelemetry, Micrometer, Prometheus, Grafana | traces through the graph, metrics at `/metrics`, and the demo's dashboard |
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

The route, the OpenAPI document at `/openapi.json` and the Swagger page at
`/api-docs` are all generated from that. The handler must answer with `ok` or
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
species and serves the result at `/stats`.

```
Adopt ─▶ shop actor ─▶ BEGIN; UPSERT pets; INSERT INTO outbox; COMMIT ─▶ actor changes      both rows, or neither
                              │
   relay: tick ─▶ BEGIN; SELECT … FOR UPDATE SKIP LOCKED
                   ─▶ publish ─▶ DELETE what the bus took; COMMIT     at least once
                          │
               refused ◀──┴──▶ bus ─▶ projection ─▶ /stats
          (stays, next tick)          dedupe by seq, fold
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
- **Every statement is ExoQuery**, apart from the `CREATE TABLE` the pool runs
  when it opens. The insert is `insert<OutboxRow> { setParams(row).excluding(seq) }.returning { it.seq }`.
  ExoQuery has no locking clause, so the claim is a `@SqlFragment` that wraps
  the query in a free block:
  ```kotlin
  @SqlFragment
  fun <T> forUpdateSkipLocked(rows: SqlQuery<T>): SqlQuery<T> = sql {
      free("$rows FOR UPDATE SKIP LOCKED").asPure<SqlQuery<T>>()
  }
  ```
- **The SQL is built separately, in `outbox-table/`.** ExoQuery's compiler
  plugin is built for Kotlin 2.3.0 and does not load in 2.4.10: it fails with a
  `ClassCastException` while registering, and Terpal fails the same way. So the
  table, the row and the queries are in an included build on Kotlin 2.3.0,
  which has its own Kotlin Gradle plugin. `app`, on 2.4.10, depends on it as
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
val petshop: Module = settings + telemetry + database + registry + theShop + arrivals + events + web

object Petshop : LarkApp<PelicanServer>() {
    override val module: Module = petshop
    override fun AppScope.run(root: PelicanServer) { root.block() }
}
```

`main` is one line. The root is a value that can be read without running
anything. The compiler plugin checks the graph against it as you type, and
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
which mean "could not ask".

## Tests

Every test in `app` is written as a story: `Given`, `When`, `Then`, `And` and
`But` each run a block, time it and return its value, so the next step can
check what the last one did. A failing step ends the story with an
`AssertionError` whose message is the story up to that step, so CI and the
JUnit XML show where it broke. The console output is coloured under
`FORCE_COLOR` or in IntelliJ. The code is Lark's
`lark-test` module, which grew out of a prototype in this repository.

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
| Pelican | Worth it. It gave the most benefit here for the least effort |
| Lark, the application graph | Worth it for a service with several resources and tests that start parts of it. It does not reduce the amount of code, but it rules out a set of mistakes |
| Lark, streams | Worth it where the background work depends on time: the relay is tested on a clock the test controls, and restarts itself |
| Lark, schedules and waiting | Worth it. Small, and blocking, which lets the tests wait without coroutines |
| Lark, Kafka | Worth it. A topic is a stream like the others, and an offset is committed only once its event is handled |
| Proofload | Worth it. The least effort of anything here: correctness under load in a few lines |
| kimney | Worth it once events or the API have a wire format of their own; a missing field is a compile error |
| ExoQuery | Not here, yet. The type checking is real, but for six statements it costs a separate build on an older Kotlin |

### Pelican

Verdict: worth it. One contract value gives the routes, the document, the
registry's client, its stubs and a typed test client, and its limits rarely
get in the way.

**What it gives.** Every failure an endpoint declares is in its handler's type,
so the `when` over the shop's errors is exhaustive and a new error causes a
build failure instead of a 500. The OpenAPI document cannot get out of date,
because it is the only description of the API; nothing here is written in
YAML. The chip registry's client is generated from its contract and checked on
every build. Its tests stub it through the same endpoint values, so when the
contract changes the stubs change with it, or stop compiling. The end-to-end
test and the load test call the shop through a typed client, with no URLs or
status codes, and a failure arrives as the value the endpoint declared.

**What it costs.** Two things to look up once: `errorJson` for a declared
failure, and the import for `orFail`.

**Its limits.** A status can have only one response, so `NotRecorded` and an
unreachable registry share one 503 and are told apart by the message. Pelican
spec 0063 lets two failures share a status, told apart by a tag in the body. A
declared status whose body does not match the declared shape is not treated as
that failure: the generated client cannot decode it and throws
`ApiCallFailed`, which the shop treats as the registry being unreachable. A
stand-in that answers a bare 404, where the contract says a 404 carries a
`Problem`, gets `registry_down` instead of `not_chipped`.

### Lark: the application graph

Verdict: worth it for a service that holds several resources and whose tests
start parts of it. It does not reduce the amount of code. What it does is
prevent the mistakes listed below.

**What it gives.** Each of these has a test in this repository:

- **A pet is sold once.** Twenty adopters race for one tortoise on a subgraph
  that binds no port, and exactly one wins. Under load, two hundred requests a
  second try to adopt a pet that is already gone, and every one is told so.
- **An actor cannot answer with null.** The shop is a `lark-actor` behaviour,
  and a `Reply<A : Any>` does not accept a nullable type, so "no such pet" is
  an `Option<Pet>` and a `null` answer does not compile.
- **The wiring is checked at compile time.** A missing key is a compiler
  error on the recipe that asked for it, and `larkWiring` runs every graph on
  `check`.
- **Unused dependencies are caught.** A test asserts that every node that
  takes the settings uses them, and `overriding` refuses a fake under a key the
  graph does not hold.
- **Nothing is left open.** The port, the actor system, the pool and the SDK
  are released in reverse dependency order, which is what lets the load test
  start the whole application twice in one process and release everything.
- **A bad configuration file reports all of its errors at once**, in
  Typesafe Config's words, naming the file and the line.
- **Metrics are plain calls.** Counting an adoption is
  `counter("petshop.adoptions").increment()`, with the outcome as a label, so
  one query answers how many adoptions there were and how many were refused.
  No graph node is needed.
- **Log lines about a pet can be searched by that pet.** `adopt` annotates
  `pet_id` and `adopted_by` instead of writing them into the message, the
  refusal carries the same pair, and the pair survives the fork the ask runs on
  and reaches Logback's MDC.

**What it costs.** `app/src/main/kotlin/petshop/app/Wiring.kt` is about 260
lines, much of it comments, for a graph that a hand-written `main` might wire
in fewer. `singleOf(::Thing)` shortens a node that is a plain constructor call,
but few nodes are: most are a resource with a release, a factory, an actor, a
stream being run or a config section.

**Its limits.** The compiler plugin that shows errors in the editor is written
against Kotlin's compiler internals, which have no stability promise, so it is
the part of this stack most likely to break on a Kotlin upgrade. It only reads
a graph from source it can see:

- IntelliJ runs it in the editor only once
  `kotlin.k2.only.bundled.compiler.plugins.enabled` is unchecked in the
  registry.
- A module that comes from another Gradle module has no source to read.
- An incremental compile re-reads only what changed, so it usually skips a
  graph spread over several files.

It reports when it has not read a graph. `larkWiring` runs the graph, is not
affected by any of this, and always runs.

### Lark: streams

Verdict: worth it for background work that depends on time. The relay is the
clearest example: it is tested an hour at a time on a clock the test controls,
restarted after a defect, and run on whichever backend the graph names. Every
stream here, including the in-process bus's readers, runs on Forks.

**What it gives.**

- **Blocking calls are easy to place.** `mapPar` runs the relay's JDBC claim
  on a virtual thread, with no dispatcher held and no `CompletionStage` built
  by hand.
- **Refusals are handled as values.** An event the bus turns away is logged,
  counted and left in the outbox for the next tick, and the stream's failure
  type stays `Nothing`.
- **Defects restart the stream.** `restartOnDefect` runs the relay again after
  a claim that throws, and the rolled-back claim loses nothing.
- **The in-process bus is a library type.** `Hub` sends what one publisher
  publishes to every reader on any backend, keeps what is published before the
  first reader arrives, and refuses instead of blocking when a reader is full.
- **A run can be stopped from outside.** `Run.start` returns a `Running`, whose
  `close` is the node's release.
- **The same description runs on more than one backend.** The binding that
  picks the backend is one line (Forks for everything here), and the relay's
  tests run the same description on a clock the test controls.

**Its limits.** Forks' `stop` interrupts the loop, which should
cut short a claim blocked on Postgres and roll it back; no test here checks
that yet.

### Lark: schedules and waiting

Verdict: worth it. A few lines, no `suspend`, and a clock a test can control.

`Schedule.spaced(…) zipLeft Schedule.upTo(…)`, retried, waits for something
eventually consistent without `suspend`, rethrows the last failure, and runs
on a clock a test can control. `lark-test`'s `eventually` is the same thing as a
step, and its stories are what every test here is written in.

### Proofload

Verdict: worth it, and the least effort of anything here.

There are three load tests, each a scenario, an assertion and an HTML report,
against the whole service started in-process:

- browsing at two hundred requests a second, after two seconds of unrecorded
  warm-up, with the shop's own service time held under 100 ms at p99;
- the rush on a pet already adopted, which checks correctness;
- two instances on one outbox table, whose two relays must publish every event
  exactly once between them.

The report says whether the load generator kept to its own schedule, which
tells you whether a p99 reflects the shop or the tool. That is why the test
asserts the shop's service time: on a busy laptop the generator fell 126 ms
behind at p99, and the response time it measured was twice the budget while
the shop's own was within it. The load tests run as `./gradlew loadTest`,
separate from `test` and `build`, because measurements should be taken on a
quiet machine. Each test is a few lines on top of the graph and the typed
client.

### Lark: Kafka

Verdict: worth it. A consumer that commits only what it handled, and dead
letters, are parts a service would otherwise write itself and could easily get
wrong.

**What it gives.** Kafka is a `Stream` like any other, run on either backend,
and `runCommitting()` commits an offset only once its record has reached the
end of the stream, so the projection never commits an event it has not folded
in. A record that will not decode is a `Left` that keeps its offset, so it is
routed to dead letters instead of stopping the consumer, and `deadLetters`
writes it unchanged, with its origin in the headers. The producer's failure is
a value, `PublishFailed`, which the shop turns into the `BusRefused` the relay
already handles.

**What it costs.** Confluent's serializer is not on Maven Central, so the build
adds Confluent's repository, limited to `io.confluent`. avro4k's serde needs
Confluent 8.3 or later, which asks for its own `8.3.0-ccs` build of the Kafka
4.3 client, so the build pins Apache's `4.3.0` to keep one client on the
classpath. `lark-kafka` is built against 3.8 and runs on it; `KafkaBusSpec`
is what says so. The test broker is still the 3.8 image, which a 4.3 client
talks to.

**Its limits.** Delivery is at least once, not exactly once: `lark-kafka` has
no transactions, so a consumer that dies after folding an event and before
committing sees it again. This is why the projection dedupes by `seq`.

### kimney

Verdict: worth it once the events have a wire format of their own, which Kafka
gives them.

**What it gives.** The domain events and their wire records are separate types,
and so are the HTTP API's DTOs ([`api/.../Dtos.kt`](api/src/main/kotlin/petshop/api/Dtos.kt)),
and every mapping between them is derived at compile time. A field one side
cannot fill, a species the DTO does not have, or a failure added to
`PetShopError` without a place in `ProblemDto` is a compile error on the call
that uses it, instead of a null or a new value on the wire, and the error
message names the fix. The errors show in IntelliJ as you type once
Help → Find Action → Registry has
`kotlin.k2.only.bundled.compiler.plugins.enabled` unchecked, the same setting
Lark's editor errors need.

**What it costs.** A compiler plugin, applied in `api`'s and `app`'s builds,
that supports Kotlin 2.4 and stops the build on any other minor version until
a release supports it.

### ExoQuery

Verdict: not here, yet. The outbox and the catalogue have six statements
between them. Having them type checked is a real benefit, but a separate build on Kotlin 2.3.0, a conversion
layer and a `free` block for the locking clause cost more than the SQL they
replace. Worth another look when it loads on the project's Kotlin version, or
for a project with many more queries.

**What it gives.** Every statement against the outbox except its
`CREATE TABLE` is Kotlin, checked when it compiles. The locking clause, which
it has no syntax for, is a `@SqlFragment` around a `free` block, so it is
written once and used like any other query.

**What it costs.** Its compiler plugin is built for Kotlin 2.3.0 and does not
load in 2.4.10, so the table, the row and the queries live in an included build
on 2.3.0, and `app` converts events to rows and back.

## Problems found along the way

**Type checks in three layers can still miss a null.** Kotlin, Pelican and the
actor protocol can all accept an actor answering "no such pet" with `null`,
while the actor runtime underneath refuses a null message at run time, as
Pekko's does. Only running it finds that. A declared failure depends on every
layer beneath it handling it as well. Fixed: the shop's actor is a
`lark-actor` behaviour, whose replies cannot be null.

**Log annotations that cross a fork can still be lost at the logging backend.**
Writing the pairs into the message reads correctly to a person, but
`%X{pet_id}`, a JSON encoder and field searches see nothing. `lark-slf4j` puts
them in the MDC for the call and restores the map afterwards, because the
thread comes from a pool and is reused. Fixed by `lark-slf4j`.

**A test worker does not see the shell's environment.** `FORCE_COLOR=1
./gradlew test` sets nothing in the JVM the tests run in.
`app/build.gradle.kts` passes `FORCE_COLOR`, `NO_COLOR` and `lark.test.colour`
on. Lark spec 0118 has the wiring plugin do this for every test task.

**Demo stubs must follow the contract too.** The demo's registry stand-in has
to answer a 404 with the `Problem` body the contract declares, or the shop
reports the registry as down. Pelican spec 0062 generates the demo's mapping
files from the same typed stubs the tests use.

## Versions

Pelican `1.0.0-RC3`, Lark `0.9.0` (its Gradle wiring plugin `0.2.0`), Proofload
`0.1.0-rc4`, ExoQuery `2.0.4.PL`. Pekko `1.2.1`, Pekko HTTP `1.3.0`, Arrow
`2.1.2`, Testcontainers `2.0.5`, PostgreSQL driver `42.7.13`, HikariCP `7.1.0`,
OpenTelemetry SDK `1.51.0`, Micrometer's Prometheus registry `1.12.0`, Logback
`1.5.20`, Kotest `6.2.4`, JUnit `6.1.3`. kimney `0.3.0`, avro4k `2.12.0`,
Confluent's Avro serializer `8.3.0` with avro4k's Confluent serde, Kafka
client `4.3.0`, the
`apache/kafka-native:3.8.0` image for tests. Kotlin 2.4.10 (2.3.0 for `outbox-table/`), JDK 25 (21 for `registry/`, which Pelican's check loads in Gradle's JVM, and `outbox-table/`).

All five libraries being tried out are at an early stage, and say so.
