# Petshop

A small pet shop, built to find out how well a set of Kotlin libraries work
together on a real service: an HTTP API with an OpenAPI document, an actor that
owns the state, background work as streams, a transactional outbox in Postgres,
a dependency graph that starts and stops it all, and tests that run the whole
thing in-process, under load and as Given/When/Then stories.

The evaluation below is of the libraries and this repository as they are now.

```bash
docker compose -f demo/docker-compose.yml up -d postgres registry
./gradlew :app:run          # http://127.0.0.1:8080, docs at /api-docs
./gradlew :app:test         # every test that builds the shop starts its own Postgres
./gradlew :loadtest:test    # 200 requests a second at the real graph
```

`demo/` adds Prometheus and Grafana watching the running shop; see `demo/README.md`.

## What it is built with

The libraries under evaluation:

| Library | What it does here |
|---|---|
| [Pelican](https://github.com/matthewjones372/pelican) | the shop's HTTP contract as values: routes, the OpenAPI document and Swagger page, handlers that must answer a declared failure; the chip registry's client generated from its contract; typed WireMock stubs, golden files and a typed test client |
| [Lark](https://github.com/matthewjones372/lark) | the application as a dependency graph that validates, subsets, starts and stops itself (`lark-app`, its Pekko, Typesafe Config and Gradle wiring-check modules); the background work as `Stream`s on Pekko or Lark's own Forks (`lark-stream`); retries on schedules; logs, metrics and traces that cross a fork (`lark-slf4j`, `lark-micrometer`, `lark-otel`) |
| [Proofload](https://github.com/matthewjones372/proofload) | load tests that start the whole service in-process, assert correctness under load, and write an HTML report |
| [ExoQuery](https://github.com/ExoQuery/ExoQuery) | every statement against the outbox table, written as Kotlin and checked at compile time |

What they run on, and what the tests use:

| | |
|---|---|
| Apache Pekko | the shop's actor, the HTTP server and client, the stream backend the arrivals and the projection run on, and the in-process bus |
| PostgreSQL, HikariCP | the outbox table |
| Testcontainers | a real Postgres for every test that builds the shop, a fresh schema per graph |
| WireMock | the chip registry in tests and the demo, stubbed through Pelican's `pelican-test-wiremock` |
| Kotest assertions on JUnit 6 | every test; the end-to-end and app tests read as stories (below) |
| Arrow | `Either` and `Raise` for declared failures, from the domain up |
| OpenTelemetry, Micrometer, Prometheus, Grafana | traces through the graph, metrics at `/metrics`, and the demo's dashboard |
| Logback | where Lark's log lines and Pekko's end up, through `lark-slf4j` |

## What it is

| Module | What it holds | Built with |
|---|---|---|
| `domain` | `Pet`, `PetShop`, `ChipRegistry`, and the ways adopting can fail | Arrow |
| `registry` | the chip registry's contract as endpoint values, and the client generated from it | Pelican |
| `api` | the endpoints, their failures, the handlers | Pelican, Pekko HTTP |
| `app` | the actor, the arrivals stream, the chip registry's client, the outbox and its relay, the bus and its consumer, the wiring, `main` | Lark, Pekko |
| `outbox-table` | the outbox's row and its SQL, in an included build on the Kotlin ExoQuery is built for | ExoQuery |
| `loadtest` | the shop under load, started in-process | Proofload |

### The contract is a value

```kotlin
val adoptPet = endpoint(petId) {
    post("pets" / petId / "adoption")
    summary = "Take a pet home"
    json<Pet>().orFail(petMissing, petTaken, petNotChipped, unavailable)
}
```

The route, the OpenAPI document at `/openapi.json` and the Swagger page at
`/api-docs` all come from that. The handler must answer with `ok` or one of the
four declared failures. A `when` over the sealed error type is exhaustive, and
returning a failure the endpoint never declared does not compile.

### One writer, no locks

Two people adopting the same tortoise is the race the shop has to lose on
purpose. An actor handles one message at a time, so the second adopter is told
the pet is taken rather than both being told yes. There is no lock anywhere in
this repository.

### The background work is a stream

New pets keep arriving. `Stream.tick(...)` into the actor, with the failure the
feed can end with in its type: `Stream<Nothing, Pet>` says this one cannot fail.

### What happened leaves through an outbox

Every arrival and every adoption is also an event, and something else in the
service reads them: a projection that tallies arrivals and adoptions by species
and serves the result at `/stats`.

```
Adopt ─▶ shop actor ─▶ INSERT INTO outbox ─▶ pet changes       only if the row went in
                              │
   relay: tick ─▶ BEGIN; SELECT … FOR UPDATE SKIP LOCKED
                   ─▶ publish ─▶ DELETE what the bus took; COMMIT     at least once
                          │
               refused ◀──┴──▶ bus ─▶ projection ─▶ /stats
          (stays, next tick)          dedupe by seq, fold
```

- **The outbox is a Postgres table.** The actor writes the event's row first
  and changes the pet only once the row is in, so the shop never sells a pet it
  has not recorded selling. If the write throws, the actor carries on as it was
  and answers `NotRecorded`, a 503: the pet is still on the shelf, and the
  registry is never asked. That 503 is the same response as a registry that
  cannot be reached (`Unavailable`, with a message saying which), because
  Pelican lets a status name only one response.
  `seq` is an identity column, so it keeps counting across restarts.
- **Every statement is ExoQuery**, apart from the `CREATE TABLE` the pool runs
  when it opens. The insert is `insert<OutboxRow> { setParams(row).excluding(seq) }.returning { it.seq }`.
  The claim is a `@SqlFragment` that wraps the query in a free block, because
  ExoQuery has no locking clause of its own:
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
  which has its own Kotlin Gradle plugin. `app`, on 2.4.10, depends on it
  as `petshop:outbox-table` and converts events to rows and back
  (`PostgresOutbox`). When ExoQuery ships a plugin for Kotlin 2.4,
  `outbox-table/` can move back into `app`.
- **A claim is one transaction.** The relay selects the oldest hundred rows
  `FOR UPDATE SKIP LOCKED`, offers each to the bus while it still holds them,
  deletes the ones the bus took, and commits. Two relays (two instances, or a
  restarted one beside one still finishing) each take the rows the other has
  not locked. Neither waits for the other, and they never publish the same row
  at the same time. If the process dies before the commit, the locks go with
  the connection and the rows are claimed again. `PostgresOutboxSpec` holds one
  claim open and shows a second one skipping past it. The load test runs two
  whole instances on one table while it browses both. Each instance's
  projection sees only its own relay's bus, so the two tallies must add up to
  exactly the number of events recorded. With the locking clause removed, they
  add up to more.
- **The relay is a stream, run on Lark's Forks.** `Stream.tick` makes the claim on a
  virtual thread (`mapPar`, because JDBC blocks), and publishes inside it, while the rows
  are held. A refusal is logged and counted, and the
  event stays in the table for the next tick. `restartOnDefect` starts the
  relay again after a claim that throws, and the rolled-back claim loses nothing.
- **The bus** is a bounded queue into a Pekko `BroadcastHub` in the same process,
  standing where a broker would. It refuses the way one does: when full, and
  once closed.
- **The consumer** is `bus.subscribe()`: `statefulMap` remembers every `seq` it
  has seen, because at-least-once means some arrive twice, and `scan` folds the
  rest into a `Tally`. `/stats` reports how many duplicates it dropped.

The pets are the actor's, in memory. The table is what makes
the events durable: a restart forgets the catalogue, but not an event it
recorded and had yet to publish. The order within one relay is the order the
events were recorded. With more than one relay, each one's batch is in order
and the batches interleave.

Running it needs a Postgres. `demo/docker-compose.yml` starts one with the
credentials `application.conf` expects, and every test that builds the shop
starts its own through Testcontainers, with a fresh schema per graph.

### The application is a value

```kotlin
val petshop: Module = settings + telemetry + database + registry + theShop + arrivals + events + web

object Petshop : LarkApp<PelicanServer>() {
    override val module: Module = petshop
    override fun AppScope.run(root: PelicanServer) { root.block() }
}
```

`main` is one line, and the root is a value read without running anything —
which is what the compiler checks the graph against as you type, and what
`larkWiring` checks it against by running it.

The load test starts the same value in its own process, runs two thousand
requests through it, and gets the port back afterwards.

### Somebody else's service

An adoption is recorded with the national chip registry: a `GET` for the pet's
chip, then a `POST` naming its new keeper. The actor settles who gets the pet
first, so the losers of a race never reach the registry, and a registry that
cannot record the keeper puts the pet back on the shelf.

The registry's contract is written the way the shop's own is, as values, in
`registry`. The client is generated from them by Pelican's Gradle plugin,
committed, and checked on every build; `HttpChipRegistry` is what is left, the
shop's decision about which answers mean "no chip" and which mean "could not ask".

## What a test looks like

Every test in `app` reads as a story: `Given`, `When`, `Then`, `And` and `But`
each run a block, time it and answer its value, so the next step checks what the
last one did. A failing step ends the story with an `AssertionError` whose
message is the story up to that step, so CI and the JUnit XML show where it
broke. The console copy is coloured under `FORCE_COLOR` or IntelliJ. The code
is a prototype in `app/src/test/kotlin/petshop/app/Story.kt`; Lark specs 0115
and 0116 move it into a `lark-test` module.

The end-to-end test is the whole graph `main` starts, with two nodes swapped:
where the registry is, and which database the outbox is in. The shop is called
through its own endpoints, so there is no URL, status code or JSON in the test,
and the database is a real Postgres the test reads as well as the API:

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

`use` gives the port, the actor system and the pool back when the block
returns, so there is no teardown to write. `eventually` blocks rather than
suspends, because `use`'s block is not `suspend`, and gives up on time by Lark's
clock.

A test of one part starts only that part. `subgraph` cuts the graph to what a
node is reached through, and a collaborator is replaced in one of two places,
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

The relay's tests run it on a clock the test owns (`lark-stream-test`), so an
hour of ticks is one `adjust` and nothing sleeps. What the shop has promised
its callers is a set of golden files: `golden.operations(api.spec())` fails on a
change that would break somebody already calling.

The load test asks a correctness question under load, through the same typed
client:

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

## How each one holds up

### Pelican

**What it gives.** Every failure an endpoint declares is in its handler's type,
so the `when` over the shop's errors is exhaustive and a new error is a build
failure rather than a 500. The OpenAPI document cannot drift, because there is
no second description to drift from; nothing here writes YAML. The chip
registry's client is generated from its contract and checked on every build.
Its tests stub it through the same endpoint values, so a contract change moves
the stubs too, or stops them compiling. The end-to-end test and the load test
call the shop through a typed client: no URL, no status code, and a failure
arrives as the value the endpoint declared.

**What it costs.** Two things to look up once: `errorJson` for a declared
failure, and the import for `orFail`.

**Its limits.** A status names one response, so `NotRecorded` and an
unreachable registry share one 503, told apart by its message. A declared
status whose body is not the declared shape is not that failure: the generated
client cannot decode it and throws `ApiCallFailed`, which the shop treats as
the registry being unreachable. A stand-in that answers a bare 404 where the
contract says a 404 carries a `Problem` gets `registry_down`, not `not_chipped`.

### Lark: the application graph

**What it gives.** Each of these is a test in this repository:

- **A pet is sold once.** Twenty adopters race for one tortoise on a subgraph
  that binds no port, and exactly one wins. Under load, two hundred a second try
  to adopt one already gone, and every one is told so.
- **An actor cannot answer with null.** Pekko refuses a null message, so a
  nullable reply would fail at run time. `ask` binds its reply to `Any`, so a
  nullable reply type does not compile, and the shop's `Find` answers an
  `Option<Pet>`.
- **The wiring is checked as the code compiles.** A missing key is a compiler
  error on the recipe that asked for it, and `larkWiring` runs every graph on
  `check`.
- **A dependency nothing reads cannot hide.** A test asserts every node that
  takes the settings uses them, and `overriding` refuses a fake under a key the
  graph does not hold.
- **Nothing is left open.** The port, the actor system, the pool and the SDK
  are released in reverse dependency order, which is what lets the load test
  start the whole application twice in one process and get everything back.
- **A bad configuration file says everything wrong with it at once**, in
  Typesafe Config's words, naming the file and the line.
- **A number is a call, not a node.** Counting an adoption is
  `counter("petshop.adoptions").increment()`, with the outcome as a label, so
  one query answers how many adoptions and how many were refused.
- **A line about one pet can be found by that pet.** `adopt` annotates `pet_id`
  and `adopted_by` rather than spelling them into the message, the refusal
  carries the same pair, and the pair survives the fork the ask runs on and
  reaches logback's MDC.

**What it costs.** `app/Wiring.kt` is about 240 lines, much of it comments, for
a graph a hand-written `main` might do in fewer. It does not delete code; it
makes a set of mistakes impossible. `singleOf(::Thing)` shortens a node that is
a plain constructor call, and few are: most are a resource with a release, a
factory, an adapter between Pekko systems, a stream being run or a config
section.

**Its limits.** The compiler plugin behind the in-editor error is written
against Kotlin's compiler internals, which have no stability promise, so it is
the part of this stack that can break on a Kotlin upgrade. It reads a graph
only from source it can see:

- IntelliJ runs it in the editor only once
  `kotlin.k2.only.bundled.compiler.plugins.enabled` is unchecked in the
  registry.
- A module arriving from another Gradle module has no source to read.
- An incremental compile re-reads only what changed, so a graph spread over
  several files is usually one it declines.

It says when it has not read a graph rather than staying quiet. `larkWiring`
runs the graph, is unaffected by any of this, and is the check that always
runs.

### Lark: streams

**What it gives.**

- **A blocking call has an obvious home.** `mapPar` runs the relay's JDBC claim
  on a virtual thread, with no dispatcher held and no `CompletionStage` built
  by hand.
- **A refusal is not a failure.** An event the bus turns away is logged,
  counted and left in the outbox for the next tick, and the stream's type
  stays `Nothing`.
- **A defect is not the end.** `restartOnDefect` runs the relay again after a
  claim that throws, and the rolled-back claim loses nothing.
- **An idempotent consumer is two operators.** `statefulMap` carries the `seq`s
  seen and `scan` the tally, as plain Kotlin values.
- **A run stops from outside.** `Run.start` answers a `Running`, whose `close`
  is the node's release.
- **One description, more than one backend.** The relay runs on Lark's Forks,
  and the arrivals and the projection on Pekko, because the bus is a Pekko hub.
  The binding that picks the backend is one line, and the relay's tests run the
  same description on a test clock.

**Its limits.** A Pekko-native source runs only on Pekko, so whatever reads the
in-process bus is pinned to it. Forks' `stop` interrupts the loop, which should
cut a claim blocked on Postgres short and roll it back; no test here holds that
yet.

### Lark: schedules and waiting

`Schedule.spaced(…) zipLeft Schedule.upTo(…)`, retried, waits for something
eventually consistent without `suspend`, rethrowing the last failure, on a
clock a test can drive. `upTo` and `lark-test`'s `eventually` and stories are
on Lark's `main` and not yet in a release, which is why the story code here is
a prototype in `app`'s tests.

### Proofload

Three load tests, each a scenario and an assertion and an HTML report, against
the whole service started in-process:

- browsing at two hundred a second, after two seconds of warm-up that are not
  recorded;
- the rush on a pet already gone, a correctness claim;
- two instances on one outbox table, whose two relays must publish every event
  exactly once between them.

The report says whether the generator kept its own schedule, which decides
whether a p99 belongs to the shop or the tool. It was the least work of
anything here: each test is a few lines on top of the graph and the typed
client.

### ExoQuery

**What it gives.** Every statement against the outbox but its `CREATE TABLE`
is Kotlin, checked when it compiles. The locking clause it has no syntax for is
a `@SqlFragment` around a `free` block, so it is written once and used like any
other query.

**What it costs.** Its compiler plugin is built for Kotlin 2.3.0 and does not
load in 2.4.10, so the table, the row and the queries live in an included build
on 2.3.0, and `app` converts events to rows and back.

## Sharp edges

**Three type systems can agree on something that fails.** Kotlin, Pelican and
the actor protocol all accepted an actor answering "no such pet" with `null`,
and Pekko refuses a null message at run time. Running it is what finds that.
`ask` makes a nullable reply a compile error, and the general point
stands: a declared failure is only as good as everything beneath it.

**An annotation that crosses a fork can still be lost at the backend.**
Flattening the pairs onto the message reads correctly to a person, while
`%X{pet_id}`, a JSON encoder and every field search see nothing. `lark-slf4j`
puts them in the MDC for the call and restores the map after it, because the
thread is one a pool hands on.

**A test worker does not see the shell's environment.** `FORCE_COLOR=1
./gradlew test` sets nothing in the JVM the tests run in. `app/build.gradle.kts`
hands `FORCE_COLOR`, `NO_COLOR` and `lark.test.colour` on.

**A demo stub must keep the contract too.** The demo's registry stand-in has to
answer a 404 with the `Problem` body the contract declares, or the shop reports
the registry as down.

## Versions

Pelican `1.0.0-RC3`, Lark `0.7.0` (its Gradle wiring plugin `0.2.0`), Proofload
`0.1.0-rc4`, ExoQuery `2.0.4.PL`. Pekko `1.2.1`, Pekko HTTP `1.3.0`, Arrow
`2.1.2`, Testcontainers `2.0.5`, PostgreSQL driver `42.7.13`, HikariCP `7.1.0`,
OpenTelemetry SDK `1.51.0`, Micrometer's Prometheus registry `1.12.0`, Logback
`1.5.20`, Kotest `6.2.4`, JUnit `6.1.3`. Kotlin 2.4.10 (2.3.0 for
`outbox-table/`), JDK 21.

All four libraries under evaluation are early, and say so.
