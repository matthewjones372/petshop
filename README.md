# Petshop

A small service built with [Pelican](https://github.com/matthewjones372/pelican),
[Lark](https://github.com/matthewjones372/lark) and
[Proofload](https://github.com/matthewjones372/proofload) — three Kotlin
libraries that have not been used together before. It exists to find out whether
they help, and the answer at the bottom is what was actually observed rather
than what was hoped for.

It is a real service: an HTTP API with an OpenAPI document, an actor that owns
the state, a stream of background work, a dependency graph that starts and stops
it, and a load test that runs the whole thing in its own process.

```bash
docker compose -f demo/docker-compose.yml up -d postgres registry
./gradlew :app:run          # http://127.0.0.1:8080 — docs at /api-docs
./gradlew :loadtest:test    # 200 requests a second at the real graph
```

## What it is

| Module | What it holds | Library |
|---|---|---|
| `domain` | `Pet`, `PetShop`, `ChipRegistry`, and the ways adopting can fail | none |
| `registry` | the chip registry's contract as endpoint values, and the client generated from it | Pelican |
| `pelican-wiremock` | WireMock, stubbed and verified in endpoint values rather than URLs | Pelican, WireMock |
| `api` | the endpoints, their failures, the handlers, the DTOs | Pelican, kimney |
| `app` | the actor, the arrivals stream, the chip registry's client, the outbox and its relay, the bus and its consumer, the wiring, `main` | Lark |
| `outbox-table` | the outbox's row and its SQL, in an included build on the Kotlin ExoQuery is built for | ExoQuery |
| `loadtest` | the shop under load, started in-process | Proofload |

### The contract is a value

```kotlin
val adoptPet = endpoint(petId) {
    post("pets" / petId / "adoption")
    json<Pet>().orFail(petMissing, petTaken)
}
```

The route, the OpenAPI document at `/openapi.json` and the Swagger page at
`/api-docs` all come from that. The handler must answer with `ok`, `petMissing`
or `petTaken` — a `when` over the sealed error type is exhaustive, and returning
a failure the endpoint never declared does not compile.

### The wire is not the domain

`Pet` stays in `domain`; the endpoints answer a `PetDto`, and the declared
failures are a sealed `ProblemDto`. [kimney](https://github.com/matthewjones372/kimney)
writes each crossing at compile time:

```kotlin
fun Pet.toDto(): PetDto = transformInto()                // PetId unwrapped, Species by name
fun List<Pet>.toDto(): List<PetDto> = transformInto()
fun PetShopError.toDto(): ProblemDto = into<_, ProblemDto>()
    .withSealedCaseRenamed(RegistryDown::class, ProblemDto.Unavailable::class)
    .withSealedCaseRenamed(NotRecorded::class, ProblemDto.Unavailable::class)
    .transform()                                          // the rest by name
```

The JSON is unchanged — `id` was already a plain number. What changed is that a
species added to the domain, or a field added to a DTO, is a compile error at
the crossing rather than a new value on the wire.

### One writer, no locks

Two people adopting the same tortoise is the race the shop has to lose on
purpose. An actor handles one message at a time, so the second adopter is told
the pet is taken rather than both being told yes. There is no lock anywhere in
this repository.

### The background work is a stream

New pets keep arriving. `Stream.tick(...)` into the actor, with the failure the
feed can end with in its type — `Stream<Nothing, Pet>` says this one cannot fail.

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
  which has its own Kotlin Gradle plugin. `app`, still on 2.4.10, depends on it
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
- **The relay is a stream.** `Stream.tick` makes the claim on a virtual thread
  (`mapPar`, because JDBC blocks). A refusal is logged and counted, and the
  event stays in the table for the next tick. `restartOnDefect` starts the
  relay again after a claim that throws, and the rolled-back claim loses nothing.
- **The bus** is a bounded queue into a Pekko `BroadcastHub` in the same process,
  standing where a broker would. It refuses the way one does: when full, and
  once closed.
- **The consumer** is `bus.subscribe()`: `statefulMap` remembers every `seq` it
  has seen, because at-least-once means some arrive twice, and `scan` folds the
  rest into a `Tally`. `/stats` reports how many duplicates it dropped.

The pets are still the actor's and still in memory. The table is what makes
the events durable: a restart forgets the catalogue, but not an event it
recorded and had yet to publish. The order within one relay is the order the
events were recorded. With more than one relay, each one's batch is in order
and the batches interleave.

Running it needs a Postgres. `demo/docker-compose.yml` starts one with the
credentials `application.conf` expects, and every test that builds the shop
starts its own through Testcontainers, with a fresh schema per graph.

### The application is a value

```kotlin
val petshop: Module = settings + telemetry + registry + theShop + arrivals + events + web

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

```kotlin
// twenty adopters, one tortoise, no port bound and no arrivals turning up mid-assertion
testApp(petshop.subgraph<PetShop>()) { shop: PetShop ->
    parMap((1..20).toList()) { who -> shop.adopt(PetId(1), by = "adopter $who") }
}.count { it.isRight() } shouldBe 1

// one setting changed; application.conf keeps the rest
testApp(petshop.subgraph<Settings>().overridingConfig("petshop.arrivalsEvery = 1s")) { it }
```

Somebody else's service is replaced in one of two places, depending on what the test is about:

```kotlin
// about the shop: swap the node. `overriding` refuses a key the graph does not hold,
// so a fake bound under the wrong type cannot leave the real client running beside it.
petshop.overriding(single<ChipRegistry> { FakeRegistry() }).subgraph<PetShop>()

// about the client: keep the node, swap the server. The stubs are the registry's own
// endpoints, so they move with its contract; the answers are values it declares.
@RegisterExtension val registry = PelicanWireMockExtension(JacksonCodecs)

registry.stub(lookupChip, 1L) answers ok(ChipRecord("981000000000001", keeper = "Petshop"))
registry.stub(recordKeeper, In2("981000000000001", NewKeeper("Ada"))) fails Fault.CONNECTION_RESET_BY_PEER

testApp(shopCalling(registry)) { shop: PetShop -> shop.adopt(PetId(1), by = "Ada") } shouldBeLeft RegistryDown(1)
```

and what the shop has promised its callers is a set of golden files:
`golden.operations(api.spec())` fails on a change that would break somebody
already calling, and rewrites the file on one that would not.

and what the load test asks:

```kotlin
// adopt the tortoise, then have two hundred a second try to adopt it again.
// every one must be told it is gone: a 200 in there is two people sold one pet.
exec(adoptTaken, api.post("/pets/1/adoption").expecting(409))
```

That last one is a **correctness** claim checked under load rather than a
latency one, and it is the thing this stack can say that none of the three
libraries could say alone.

## Does it help?

### Pelican: yes, clearly

The handlers compiled first time, which for HTTP code is not the usual
experience. Every failure the endpoints declare is in the handler's type, so the
`when` over `NoSuchPet` and `AlreadyAdopted` is exhaustive and a new error
member would break the build rather than escape as a 500.

The document is free and cannot drift, because there is no second description to
drift from. Nothing here writes YAML.

The cost was two lookups: `errorJson` for a declared failure and an import for
`orFail`. Both once.

Re-read after everything below: unchanged. Pelican's claim was always a
compile-time one and it was always kept, which is the least interesting verdict
here and the one that has needed the least revision.

### Lark: yes, and not for the reason first measured

What changed is what cannot happen any more, and each of these is a test in this
repository rather than a claim about one.

**A pet is sold once.** Twenty adopters race for one tortoise on a subgraph that
binds no port; exactly one wins. Two hundred a second try to adopt one already
gone; every single one is told so, and a `200` in there would fail the run. That
is a correctness claim checked under load, which is not a thing a load tool or a
test framework does alone.

**An actor cannot answer "no such pet" with null.** It did once, and the
endpoint that had carefully declared a 404 returned a 500 — everything compiled,
and only running it found the bug. `ask` binds its reply to `Any`, so the code
that did it no longer compiles, and a does-not-compile fixture in Lark holds
that.

**The wiring is checked as the code compiles.** A missing key is a compiler
error on the recipe that asked for it, and a red underline in the editor before
any build is run. A dependency nothing provides cannot reach staging, or a
commit.

**A dependency nothing reads cannot hide.** The actor took a `Settings` it never
looked at, and `render()` drew that edge as though it were real. A test asserts
every node taking the settings uses them, and `overriding` refuses a fake it is
handed.

**Nothing is left open.** The port, the actor system and the SDK are released in
reverse dependency order, which is what lets the load test start the whole
application in its own process and get the port back afterwards.

**A bad configuration file says everything that is wrong with it, at once**, in
Typesafe Config's own words, which name the file and the line.

**A number is a call, not a node.** `0.4.0` ships `lark-micrometer`, so counting
an adoption is `counter("petshop.adoptions").increment()` and nothing takes a
`MeterRegistry` as a dependency. The outcome is a label rather than three metric
names, so one query answers how many adoptions and how many were refused — and
`demo/` is Prometheus and Grafana watching exactly that.

**A line about one pet can be found by that pet.** `adopt` annotates `pet_id`
and `adopted_by` rather than spelling them into the message, and the refusal
carries the same pair as the success — so a search for one pet returns the whole
story and not the half of it that went well. The pairs survive the fork the ask
runs on, which an MDC cannot do by itself, and a test asserts that rather than
the wording.

#### What it costs

`app/Wiring.kt` is 84 lines for eight nodes, against maybe sixty written by hand
in `main`. Line count is roughly a wash and is not the point: none of the six
things above is available at sixty lines, and most of them are not available at
any number of lines without something that owns the graph.

`singleOf(::Thing)` shortens a node that is a plain constructor call, and one of
the eight here is one. The rest are a resource with a release, a factory, an
adapter between two Pekko systems, a stream being run and a config section —
which is worth knowing before expecting a dependency graph to delete code. It
does not delete code. It makes a set of mistakes impossible.

Logging was the one place the graph gave nothing back. No node takes a logger,
which is right, but `0.2.0` shipped no adapter either — so every service wrote
the same twenty lines to reach a backend, and this one had not: `logInfo` went
to stderr while Pekko's lines went through the logback already on the classpath,
in a different format, and `main` printed its start-up line with `println`.
`0.3.0` ships `lark-slf4j`, and the twenty lines became a dependency: it
registers itself, so `main` is back to one line and a test here says the
classpath still answers.

The cost that arrived with `0.2.0` is a different kind. The compiler plugin is
written against Kotlin's compiler internals, which have no stability promise, so
it is a thing that will break on Kotlin upgrades in a way the rest of this stack
will not. Lark keeps it in a module nothing else depends on and refuses to read
a graph in a compiler it was not built for, which is the right shape for that
bargain — but a service taking it on should know it is taking on a moving part
in exchange for an earlier error, and that `larkWiring` is what it would fall
back to.

### lark-stream: yes for the relay, once three rough edges were fixed

The outbox relay and the projection are the first things here to use more of
`lark-stream` than `tick` and `map`. `EventsSpec` covers the claims below: an
adoption reaches the consumer, a refused event goes again, and an event
delivered twice is counted once.

**A refusal is not a failure, and the type says so.** `publish` answers
`Either<BusRefused, ShopEvent>`, and `divertLefts` sends each `Left` to a named
sink and keeps the stream `Stream<Nothing, ShopEvent>`. In plain Pekko you would
write `divertTo` with a predicate and a cast. Here nothing is carried in the
element past the point where it stopped mattering.

**A blocking call has an obvious home.** The ask to the actor blocks. `mapPar`
runs it on a virtual thread, so no Pekko dispatcher thread is held and there is
no `CompletionStage` to build by hand.

**An idempotent consumer is two operators.** `statefulMap` carries the seen
`seq`s and `scan` carries the tally, both as plain Kotlin values. Duplicates are
counted, not hidden.

Writing the relay against `0.4.0` found three rough edges, and each became a lark
spec and a change in `0.5.0`:

- **`mapPar` on a stream with no failure type could not infer one from a body
  that never raises** ([spec 0043](https://github.com/matthewjones372/lark/blob/main/specs/0043-two-signatures-a-relay-tripped-on.md)).
  The relay had to write `mapPar<Nothing, _, _>(1)`. Now `mapPar` keeps the
  stream's failure type, and the form that reads one out of a `raise` is
  `mapParOrFail`, matching `mapOrFail`. The same spec moved `groupedWithin` onto
  `kotlin.time.Duration`, which is what `tick` takes.
- **Nothing could stop a running stream from outside**
  ([spec 0044](https://github.com/matthewjones372/lark/blob/main/specs/0044-a-run-you-can-stop.md)).
  The relay used to stop through a flag and `takeWhile`, at the next tick.
  `Run.start` now answers a `Running`, and its `close` is the node's release:
  the relay stops at once, before the bus it publishes to closes. The arrivals
  feed, which used to end only when the actor system went, stops the same way.
- **One `Died` ended the relay for good**
  ([spec 0045](https://github.com/matthewjones372/lark/blob/main/specs/0045-a-stream-that-starts-again.md)).
  A timed-out ask would have stopped the outbox draining until the process
  restarted. `restartOnDefect(schedule)` runs the same description again after
  the delay the schedule decides, with a warn line each time, and keeps the
  declared failure in the type. Starting again loses nothing here: whatever was
  not marked sent is still in the outbox.

### The wiring check: cheap, and it found nothing here

`lark-app-gradle` checks every graph in the project as it compiles and draws
each one. Applying it is one line in `app/build.gradle.kts`; declaring the
application as a value so the check knows the root it starts from is ten more
in `Wiring.kt`, and it takes seven out of `Main.kt`, which is now six lines
including imports. Call it **net ten lines** for a gate that runs on every
build.

**It found nothing in this graph**, which is the result worth reporting. No
missing key — that was already true. No key provided twice. And nothing
unreachable from `PelicanServer`, which was the check most likely to produce
noise: `Arrivals` is a background stream nothing reads, and it is *still*
reached, because the web node takes it as a dependency rather than trusting
start-up order. Eight nodes, no findings, no opt-outs needed.

**What it caught was a bug in lark**, not in this service. The report's own
bullet arrived as `?`: from JDK 19 `System.err` follows `stderr.encoding`,
which is the native encoding when a build redirects the stream, and
`-Dfile.encoding` does not reach it. That is the sort of thing only running a
tool against a real service finds.

**The report names the line**, which is the part that matters day to day.
Commenting out the actor to see what it says:

```
e: .../petshop/app/src/main/kotlin/petshop/app/Wiring.kt:92:9 lark-app: PetShop needs ActorRef<Shop>, and nothing builds it
e: .../petshop/app/src/main/kotlin/petshop/app/Arrivals.kt:29:1 lark-app: Arrivals needs ActorRef<Shop>, and nothing builds it
```

`Wiring.kt:92` is the `singleOf(::ActorPetShop)` that asked. Since Lark `0.2.0`
that is also a compiler error and a red underline on that call, which is what
the last version of this paragraph said could not be had: a module is an
expression, and nothing reads an expression until something runs it.

What changed is that a second thing now reads it — `lark-app-compiler`, a K2
checker that reconstructs the graph from the compiler's own syntax tree. It
follows `single`, `singleOf`, `actor`, `config`, `+`, `boundTo`, names in the
same file and the branches of a `when`, and abandons an application entirely on
anything else, on the grounds that a red line under working code is worse than a
fault found a moment later.

This graph is one it can read: all ten keys, the same ten `render()` draws.
Two limits are worth knowing before expecting it everywhere. IntelliJ runs no
third-party compiler plugin in the editor until
`kotlin.k2.only.bundled.compiler.plugins.enabled` is unchecked in the registry,
so the underline is opt-in per developer. And a module arriving from another
Gradle module has no source to read, so a graph assembled across modules is one
the editor stays quiet about — this one is not, but a larger service would be.

The third limit is the one that decides how much of this is real, and it is
easiest to see by breaking the graph twice. On a full compile the error above is
what arrives. On an **incremental** one, this does:

```
w: .../Wiring.kt:119:1 lark-app: this graph was not read here, and is checked by larkWiring alone: petshop/app/arrivals, which has no source here
```

An incremental compilation re-parses only what changed; everything else arrives
as symbols from the last compilation's class files, and a class file has no
initialiser to read. So a graph spread over more than one file is usually one
the compiler plugin declines, and it declines out loud rather than reporting a
sound graph. In the editor this does not arise — analysis there is always from
current sources, which is why the underline is reliable exactly where it was
wanted.

`larkWiring` is the gate, then, and not a formality. It runs the graph, so it
sees what no reader of source can, it is unaffected by any of the above, and in
the incremental case it is the only thing that catches the fault at all —
naming, in this one, both `Wiring.kt:92` and `Arrivals.kt:29`, which is better
than the compiler plugin manages even when it does read the graph. The plugin
buys earliness where it can get it. It buys no correctness, and is not asked to.

### Proofload: yes, and it was the least work

Nothing about this changed with `0.2.0`, which is worth saying rather than
leaving to be inferred: the load test is the one part of this repository that
has not been touched since it was first written.

One scenario, one assertion, one HTML report. It ran two thousand requests at
two hundred a second against the real service, and the report says whether the
generator kept its own schedule — which is the number that decides whether the
p99 belongs to the shop or to the tool.

The whole load test is thirty lines including imports.

### kimney: yes, for the drift rather than the lines

Five mappings in `api/Dtos.kt`, one line each, where the hand-written version is
a constructor call, a `when` over four species and a `when` over five failures.
At this size that saves little typing, and typing is not the point.

What it buys is that the wire cannot drift from the domain without the build
saying so. A species added to `Species`, a field added to a DTO, a failure added
to `PetShopError`: each is a compile error on the call that meets it, on an
incremental build as on a clean one, and every failure on a call arrives at
once. Adding `Rabbit`:

```
e: .../api/src/main/kotlin/petshop/api/Dtos.kt:28:27 Cannot transform Pet → PetDto:
    PetDto.species: SpeciesDto — Species.Rabbit has no entry of the same name in SpeciesDto. Map it with .withEnumEntryRenamed(Species.Rabbit, SpeciesDto.…), or send every unmatched entry to one with .withEnumFallback(SpeciesDto.…).
e: .../api/src/main/kotlin/petshop/api/Dtos.kt:30:39 Cannot transform List<Pet> → List<PetDto>:
    List<PetDto>[].species: SpeciesDto — Species.Rabbit has no entry of the same name in SpeciesDto. Map it with .withEnumEntryRenamed(Species.Rabbit, SpeciesDto.…), or send every unmatched entry to one with .withEnumFallback(SpeciesDto.…). Or map Pet → PetDto with .withTransformer(Transformer<Pet, PetDto> { … }).
```

The fix it names is the one to write: `.withEnumEntryRenamed(Species.Rabbit,
SpeciesDto.Bunny)` or `.withEnumFallback(SpeciesDto.Other)` on an `into` chain
compiles as suggested and maps as it says.

The errors show in IntelliJ as you type once the IDE may load a third-party
compiler plugin: Help → Find Action → Registry, uncheck
`kotlin.k2.only.bundled.compiler.plugins.enabled`, restart. It is the same flag
Lark's underline needs, unchecked once per developer.

#### What it costs

A second compiler plugin, with the bargain Lark's already has: it supports
Kotlin 2.4 and stops the build on another minor until a release supports it.
One setting per developer for the editor. And some repetition in the errors —
`List<Pet> → List<PetDto>` is its own call, so a broken `Pet → PetDto` is
reported once for each.

## What building it found

**A 500 where a 404 was declared.** `Find(id, replyTo: ActorRef<Pet?>)` compiles.
Pekko refuses a null message, so an actor answering "no such pet" with `null`
throws where it meant to answer, and the endpoint that had carefully declared a
404 returned a 500. Kotlin's nullable type did not stop it and neither did
Pelican's declared failure — the mistake was below both of them. The reply is an
`Option<Pet>` now.

It is worth saying plainly: **everything compiled before that bug, and the bug
was in the one place three type systems all thought was fine.** Running it is
what found it.

**Three ways a compiler plugin fails without telling anyone.** This graph is
what `lark-app-compiler` was developed against, and getting it to work here took
four attempts that all looked identical from outside — the build green, the
editor silent. A relocated `PsiElement` that the compiler has and the editor does
not; a positioning strategy that casts its source to a declaration, which the
compiler tolerates on a call and the editor answers by dropping the diagnostic
entirely; a republished snapshot served from a cached classloader; and an
incremental compilation with nothing to read. A green `compileKotlin` turned out
to be no evidence at all about the half the plugin exists for.

The consequence is in the design rather than only in the story: the plugin now
says when it has not read a graph, which is the difference between a tool that
is working and a tool that has stopped. Without that line, every incremental
build in this repository would have looked exactly like a clean bill of health.

**An annotation that survives a fork can still die at the backend.** Lark's
strongest logging claim is that `logAnnotated` carries a pair across a `parMap`
where an MDC cannot. Writing the adapter is where that claim is kept or lost,
and the obvious version loses it: flatten the pairs onto the end of the message
— which is what the library's own cookbook showed — and every line still reads
correctly to a human while `%X{pet_id}`, a JSON encoder and every field search
see nothing. The adapter puts them in the MDC for the duration of the call and
puts the previous map back, because the thread is one a pool hands to something
else next. That adapter was written here first and is now `lark-slf4j`, which is
the shorter version of what this repository is for.

The general shape of it: a propagation guarantee is only worth what the thing at
the edge does with it, and the edge is the part a service writes itself.

## Versions

Pelican `1.0.0-RC1`, Lark `0.5.0`, Proofload `0.1.0-rc4`, ExoQuery `2.0.4.PL`, kimney `0.3.0`,
Kotlin 2.4.10 (2.3.0 for `outbox-table/`), JDK 21.

`singleOf`, `boundTo`, `ask`, `config<T>`, the wiring check and the compiler
plugin that reports it as you type were all written while this repository was
being built, which is what it is for. This graph is what the compiler plugin was
developed against, and finding that it read all ten keys and named the same
missing one the running check names is what said it worked.

All three are early. Lark says so on its own front page, and this repository is
the first thing to use it for anything.
