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
val petshop: Module = settings + theShop + arrivals + web
```

`main` is four lines. The load test starts the same value in its own process,
runs two thousand requests through it, and gets the port back afterwards.

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

**It did not make the wiring shorter.** `app/Wiring.kt` is about ninety lines to
describe eight nodes. Written by hand in `main` it would be about sixty. What
the extra thirty buy is release, ordering and the in-process load test; if a
service does not want those, they are thirty lines for nothing.

**One papercut, three times.** `single<Type> { dependency: Other -> … }` does not
compile: with one type argument given, the overload that takes a dependency
cannot apply, so it has to be `single<Type, Other> { … }`. It caught me in the
wiring here and twice before that.

**`lark-stream` and `lark-app-pekko` were unremarkable**, which is the compliment.
Six lines each and nothing surprising.

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

Pelican `1.0.0-RC1`, Lark `0.1.0`, Proofload `0.1.0-rc4`, Kotlin 2.4.10, JDK 21.

All three are early. Lark says so on its own front page, and this repository is
the first thing to use it for anything.
