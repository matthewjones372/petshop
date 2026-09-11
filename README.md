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
./gradlew :app:run          # http://127.0.0.1:8080 — docs at /api-docs
./gradlew :loadtest:test    # 200 requests a second at the real graph
```

## What it is

| Module | What it holds | Library |
|---|---|---|
| `domain` | `Pet`, `PetShop`, and the two ways adopting can fail | none |
| `api` | the endpoints, their failures, the handlers | Pelican |
| `app` | the actor, the arrivals stream, the wiring, `main` | Lark |
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

### One writer, no locks

Two people adopting the same tortoise is the race the shop has to lose on
purpose. An actor handles one message at a time, so the second adopter is told
the pet is taken rather than both being told yes. There is no lock anywhere in
this repository.

### The background work is a stream

New pets keep arriving. `Stream.tick(...)` into the actor, with the failure the
feed can end with in its type — `Stream<Nothing, Pet>` says this one cannot fail.

### The application is a value

```kotlin
val petshop: Module = settings + telemetry + theShop + arrivals + web

object Petshop : LarkApp<PelicanServer>(typeOf<PelicanServer>()) {
    override val module: Module = petshop
    override fun AppScope.run(root: PelicanServer) { root.block() }
}
```

`main` is one line, and the root is a value the build reads without running
anything — which is what `larkWiring` checks the graph against.

The load test starts the same value in its own process, runs two thousand
requests through it, and gets the port back afterwards.

## What a test looks like

```kotlin
// twenty adopters, one tortoise, no port bound and no arrivals turning up mid-assertion
testApp(petshop.subgraph<PetShop>()) { shop: PetShop ->
    parMap((1..20).toList()) { who -> shop.adopt(PetId(1), by = "adopter $who") }
}.count { it.isRight() } shouldBe 1

// one setting changed; application.conf keeps the rest
testApp(petshop.subgraph<Settings>().overridingConfig("petshop.arrivalsEvery = 1s")) { it }
```

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

### Lark: mixed, and honest about which parts

**The graph earned its place twice.** The bound port is a resource, so the load
test can start the whole application in its own process and get the port back
after — that test is the best thing in this repository and it is not writable
without something that owns start-up and shutdown. And `probe` means the shop
answers `/pets` before anything is told it is ready.

**It did not make the wiring shorter, and `singleOf` did not change that.**
`app/Wiring.kt` is 84 lines for eight nodes; by hand in `main` it would be
about sixty.

`singleOf(::Thing)` and `boundTo<Interface>()` were added to Lark after this
repository was first written, precisely because two services had come out
longer. Rewriting the wiring with them moved it by **nothing**: eleven lines
added, eleven removed.

The reason is worth having. `singleOf` reads a key and its dependencies off a
constructor reference, which shortens a node that is a plain constructor call —
and **one of the eight nodes here is one**. The rest are a resource with a
release, a factory, an adapter between two Pekko systems, a stream being run,
and a config section: none is a constructor, and none gets shorter. A service's
wiring turns out to be mostly not constructor-shaped.

`ask` did help, for a different reason: it removed the
`Adapter.toTyped(system).scheduler()` line that each of three calls needed. That
is boilerplate deleted rather than a node made shorter.

**The papercut is fixed.** `single<Type> { dependency: Other -> … }` does not
compile — Kotlin has no partial type-argument inference — and it caught me three
times in three codebases. `singleOf(::Thing).boundTo<Interface>()` never gives a
type argument beside a dependency, so it has nowhere to happen.

**`lark-stream` and `lark-app-pekko` were unremarkable**, which is the compliment.
Six lines each and nothing surprising.

### The wiring check: cheap, and it found nothing here

`lark-app-gradle` checks every graph in the project on `check` and draws each
one. Applying it is one line in `app/build.gradle.kts`; declaring the
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
Deleting `telemetry` from the graph to see what it says:

```
lark-app wiring

❯ error: missing Tracer
❯     for PetShop                 Wiring.kt:180
```

Not a compile error — the graph is an expression, so nothing reads it until
something runs it. It is a failed `./gradlew build` with a line the IDE
hyperlinks, which is most of what a compile error was wanted for.

### Proofload: yes, and it was the least work

One scenario, one assertion, one HTML report. It ran two thousand requests at
two hundred a second against the real service, and the report says whether the
generator kept its own schedule — which is the number that decides whether the
p99 belongs to the shop or to the tool.

The whole load test is thirty lines including imports.

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

## Versions

Pelican `1.0.0-RC1`, Lark `0.1.1-SNAPSHOT`, Proofload `0.1.0-rc4`, Kotlin 2.4.10,
JDK 21.

Lark is a snapshot because `singleOf`, `boundTo` and `ask` were written for this
repository and are not in `0.1.0`. `./gradlew publishToMavenLocal` in Lark's
checkout installs it.

All three are early. Lark says so on its own front page, and this repository is
the first thing to use it for anything.
