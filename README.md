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

Logging is the one place the graph gives nothing back. No node takes a logger,
which is right, but `0.2.0` ships no adapter either — so every service writes the
same twenty lines to reach a backend, and this one had not: `logInfo` went to
stderr while Pekko's lines went through the logback already on the classpath, in
a different format, and `main` printed its start-up line with `println`.

The cost that arrived with `0.2.0` is a different kind. The compiler plugin is
written against Kotlin's compiler internals, which have no stability promise, so
it is a thing that will break on Kotlin upgrades in a way the rest of this stack
will not. Lark keeps it in a module nothing else depends on and refuses to read
a graph in a compiler it was not built for, which is the right shape for that
bargain — but a service taking it on should know it is taking on a moving part
in exchange for an earlier error, and that `larkWiring` is what it would fall
back to.

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
see nothing. The adapter here puts them in the MDC for the duration of the call
and puts the previous map back, because the thread is one a pool hands to
something else next.

The general shape of it: a propagation guarantee is only worth what the thing at
the edge does with it, and the edge is the part a service writes itself.

## Versions

Pelican `1.0.0-RC1`, Lark `0.2.0`, Proofload `0.1.0-rc4`, Kotlin 2.4.10, JDK 21.

`singleOf`, `boundTo`, `ask`, `config<T>`, the wiring check and the compiler
plugin that reports it as you type were all written while this repository was
being built, which is what it is for. This graph is what the compiler plugin was
developed against, and finding that it read all ten keys and named the same
missing one the running check names is what said it worked.

All three are early. Lark says so on its own front page, and this repository is
the first thing to use it for anything.
