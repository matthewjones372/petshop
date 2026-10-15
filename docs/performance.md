# Performance on each stack and codec

After this comparison petshop runs only on Jetty with kotlinx; the `petshop.stack` and `petshop.codec` switches below were removed in #74.

The shop runs on one of two stacks (`petshop.stack`, or `STACK`) and one of two codecs (`petshop.codec`, or
`CODEC`):

- **jetty**: Pelican on Jetty, lark streams on virtual threads (`Forks`), the registry called with
  `JettyHttpTransport`. No actor system is created.
- **pekko**: Pelican on Pekko HTTP, lark streams on Pekko streams, the registry called with `PekkoHttpTransport`.
- **kotlinx** or **jackson**: what reads and writes the bodies the shop serves and the registry's.

This page measures all four combinations on one machine, one at a time, and says what each choice changes.

## Summary

| | jetty + kotlinx | jetty + jackson | pekko + kotlinx | pekko + jackson |
|---|---:|---:|---:|---:|
| Browsing: highest rate tried, none fell behind | 12,000/s | 12,000/s | 12,000/s | 12,000/s |
| Browsing: p99 at 2,000/s | 0.61 ms | 0.44 ms | 0.83 ms | 0.77 ms |
| Browsing: p99 at 10,000/s | 2.8 ms | 2.1 ms | 6.8 ms | 3.7 ms |
| Browsing, out of process: requests a second | 84,169 | 78,052 | 43,293 | 43,342 |
| Adoption: highest rate kept up | 1,000/s | 1,000/s | 1,000/s | 500/s |
| Adoption: p99 at 500/s | 20 ms | 14 ms | 11 ms | 17 ms |
| Adoption: p99 at 1,000/s | 54 ms | 34 ms | 89 ms | 111 ms |
| Start-up to first answer (median of 3) | 1.39 s | 1.54 s | 1.54 s | 1.82 s |
| Peak heap used | 159 MB | 229 MB | 168 MB | 240 MB |
| CPU for 300,000 browses | 21.4 s | 22.5 s | 41.9 s | 43.2 s |
| Classes loaded | 10,662 | 12,193 | 13,624 | 15,121 |

All latencies are the shop's service time, measured by the load generator from the moment a request left to the
moment its answer arrived. One run per combination; read differences of a few tenths of a millisecond, or of one rung
on the adoption ladder, as noise.

## Machine and versions

- MacBook Air, Apple M3, 8 cores, 24 GB, macOS 26.5.2.
- Docker Desktop 29.1.3 (8 CPUs and 8 GB for its VM), idling apart from the Postgres containers below.
- JDK 25 for the load tests (Gradle's toolchain) and Temurin 25.0.2 for the footprint runs, default collector (G1)
  and default flags, apart from a 4 GB heap for the load test JVM (`loadtest/build.gradle.kts`).
- Pelican 1.0.1-SNAPSHOT, lark 0.10.2-SNAPSHOT, Proofload 0.1.1-SNAPSHOT, Pekko 1.2.1, Pekko HTTP 1.3.0, Scala
  2.13.17, Jetty 12.1.13, Jackson 2.22.3, kotlinx.serialization 1.11.0, PostgreSQL 17 (`postgres:17-alpine`).

## Method

### The two scenarios

`loadtest/src/test/kotlin/petshop/load/StackComparison.kt` holds both. Each climbs the same ladder of fixed rates:
100, 250, 500, 1,000, 2,000, 3,000, 4,000, 5,000, 6,000, 8,000, 10,000 and 12,000 requests a second. Each rung is an
open-model Proofload run held for 10 seconds after an unrecorded warm-up at the same rate (10 seconds on the first
rung, which also warms the JVM, and 5 seconds on the others). The ladder stops at the first rung where the shop fell
behind.

- **Browsing** is `GET /pets` against a catalogue of 100 pets, one shop for the whole ladder. It goes through the HTTP
  server, the shop's actor (one ask per request) and the codec writing a list of 100 pets, and touches neither the
  database nor the registry.
- **Adoption** is `POST /pets/{id}/adoption` (no request body; the pet is in the path), each request adopting a pet
  nobody has. Each adoption is an ask to the shop's actor, which writes the pet's row and its outbox event in one
  Postgres transaction, then two calls to the chip registry over the stack's HTTP client, then the answer encoded by
  the codec. The outbox relay publishes the events to the in-process bus once a second, at most 100 at a time, so above
  100 adoptions a second the outbox grows during a rung. Each rung gets a shop and a database of its own, stocked with
  exactly the pets it will adopt: the actor copies its whole map of pets on every adoption, so a single shelf large
  enough for the whole ladder (about 240,000 pets) made a 100/s rung cost about 6 ms at p50 and measured the shelf instead of
  the server.

Arrivals are switched off (`petshop.arrivalsEvery = 1h`) so the catalogue does not change during a run. The database
is a Testcontainers Postgres in Docker, and the registry is an in-process WireMock with stubs for both of its
endpoints.

The generator is Proofload on virtual threads, in the same JVM as the shop, using the JDK's HTTP client pinned to
HTTP/1.1 so neither server is offered an h2c upgrade the other might decline. The client always decodes with kotlinx,
and browsing does not decode the body at all, so the generator's own codec work is the same for every combination.

### Verdicts

A rung **kept up** when under 1% of its requests failed and the shop's p99 service time stayed under 100 ms. It **fell
behind** otherwise. A rung that kept up is marked **(s)** when the generator itself slipped its schedule: Proofload's
rule is that the generator's p99 lateness exceeded one interval between departures, which is 0.1 ms at 10,000/s. The
generator shares the JVM and the CPU with the shop, so from 1,000/s upwards a stall of a millisecond anywhere in the
process is enough, and the mark appears on most high rungs of every combination. It means the load arrived a little
lumpier than planned, not that the shop failed to answer it.

The ladder asserts no latency. Every rung is written to its table before it is judged, and the only assertion is about
correctness: every pet the client was told it adopted is adopted in the table, exactly so when every request was
answered. The older `browsing holds up at two hundred a second` test in `PetshopLoadTest` keeps its 100 ms p99
assertion as a regression check at one modest rate, which every combination passes.

### Footprint

`loadtest/footprint.sh <stack> <codec> <out>` runs the installed distribution (`./gradlew :app:installDist`) as a
process of its own, against a throwaway Postgres in Docker on an empty database:

- **Start-up**: from launching `app/build/install/app/bin/app` to the first successful `GET /pets`, polled every
  10 ms; the median of three launches.
- **Classes and jars**: a fourth launch with `-Xlog:class+load`, counting the classes loaded by the end of the run, by
  package, and the jars they came from.
- **Heap and CPU**: the same launch, with JFR recording, serves 300,000 browses from `ab -k -c 8` (closed model, eight
  keep-alive connections, three pets in the catalogue). Peak heap is the largest `heapUsed` in JFR's
  `jdk.GCHeapSummary` events; CPU is the process's CPU time over the 300,000 requests. The JVM's default heap on this
  machine is 6 GB, so G1 collects late and peak heap says as much about how much garbage a combination makes as about
  what it keeps.

### Quiet machine

Before every phase (the load test of one combination, then its footprint) the driver waited until the one-minute load
average was under 2.5 and no other process was using more than 40% of a core, and it sampled every 2 seconds during
the phase, so that a phase another process disturbed for 10 seconds or more would be run again. None was. Spotlight
indexing, and once IntelliJ, ran for a sample or two in some phases.

| Phase | Load before (1, 5, 15 min) | Load after (1, 5, 15 min) |
|---|---|---|
| jetty + kotlinx, load test | 2.12, 3.96, 6.04 | 2.20, 3.50, 5.18 |
| jetty + kotlinx, footprint | 2.20, 3.50, 5.18 | 3.99, 3.82, 5.26 |
| jetty + jackson, load test | 2.02, 3.23, 4.92 | 2.86, 3.36, 4.44 |
| jetty + jackson, footprint | 2.29, 3.18, 4.34 | 3.03, 3.29, 4.35 |
| pekko + kotlinx, load test | 2.30, 3.09, 4.24 | 3.05, 2.91, 3.76 |
| pekko + kotlinx, footprint | 2.45, 2.78, 3.70 | 5.28, 3.36, 3.88 |
| pekko + jackson, load test | 2.23, 2.86, 3.64 | 3.22, 2.75, 3.34 |
| pekko + jackson, footprint | 2.41, 2.60, 3.26 | 4.51, 3.04, 3.40 |

The load after includes the run itself; the 15-minute figures are falling because the machine had been busy with
another build before the first phase.

### Commands

```
./gradlew build
./gradlew :loadtest:loadTest --tests 'petshop.load.StackComparison' -Ppetshop.stack=jetty -Ppetshop.codec=kotlinx
# and likewise for jetty jackson, pekko kotlinx, pekko jackson; each writes
# loadtest/build/reports/proofload/<stack>-<codec>/stepped-browsing.tsv, stepped-adoption.tsv and an HTML report per rung
./gradlew :app:installDist
loadtest/footprint.sh jetty kotlinx build/footprint/jetty-kotlinx
```

## Results

Each cell is p50 / p99 / p99.9 service time in milliseconds unless it says seconds. Failures are shown where there
were any.

### Browsing

| Rate /s | jetty + kotlinx | jetty + jackson | pekko + kotlinx | pekko + jackson |
|---:|---|---|---|---|
| 100 | 0.68 / 2.1 / 48 | 0.66 / 15 / 67 | 0.83 / 1.3 / 10 | 0.80 / 1.3 / 9.9 |
| 250 | 0.51 / 7.1 / 48 | 0.57 / 11 / 56 | 0.68 / 1.5 / 6.6 | 0.65 / 3.2 / 15 (s) |
| 500 | 0.51 / 1.1 / 11 | 0.51 / 1.3 / 15 (s) | 0.61 / 1.8 / 8.9 | 0.65 / 1.4 / 7.6 |
| 1,000 | 0.44 / 1.0 / 12 (s) | 0.44 / 1.1 / 15 (s) | 0.53 / 2.0 / 16 (s) | 0.56 / 1.3 / 16 (s) |
| 2,000 | 0.20 / 0.61 / 16 | 0.18 / 0.44 / 7.1 | 0.22 / 0.83 / 9.8 (s) | 0.24 / 0.77 / 19 (s) |
| 3,000 | 0.13 / 0.33 / 5.9 | 0.13 / 0.26 / 2.5 | 0.15 / 0.83 / 15 (s) | 0.15 / 0.47 / 13 |
| 4,000 | 0.10 / 0.51 / 9.1 (s) | 0.11 / 0.18 / 5.9 | 0.11 / 0.82 / 8.0 (s) | 0.11 / 0.66 / 12 (s) |
| 5,000 | 0.08 / 0.62 / 7.0 (s) | 0.08 / 0.32 / 8.4 | 0.11 / 1.5 / 17 (s) | 0.11 / 0.57 / 5.9 (s) |
| 6,000 | 0.08 / 0.72 / 9.4 (s) | 0.08 / 0.61 / 18 (s) | 0.11 / 1.5 / 13 (s) | 0.11 / 1.2 / 23 (s) |
| 8,000 | 0.08 / 2.0 / 19 (s) | 0.08 / 0.94 / 11 (s) | 0.11 / 2.6 / 22 (s) | 0.11 / 1.4 / 11 (s) |
| 10,000 | 0.08 / 2.8 / 15 (s) | 0.08 / 2.1 / 19 (s) | 0.12 / 6.8 / 24 (s) | 0.12 / 3.7 / 23 (s) |
| 12,000 | 0.08 / 2.0 / 15 (s) | 0.09 / 2.8 / 11 (s) | 0.12 / 4.3 / 18 (s) | 0.13 / 7.6 / 29 (s) |

No combination failed a request or fell behind on browsing, so 12,000/s is where the ladder ended rather than where
any of them stopped. The p50 falls as the rate rises because at low rates the threads that serve a request have parked
and the cores have slowed down between requests; at thousands a second everything stays hot.

### Adoption

| Rate /s | jetty + kotlinx | jetty + jackson | pekko + kotlinx | pekko + jackson |
|---:|---|---|---|---|
| 100 | 2.7 / 8.6 / 21 | 2.7 / 8.1 / 29 | 3.2 / 11 / 39 | 3.2 / 11 / 33 |
| 250 | 2.3 / 11 / 26 | 2.3 / 8.0 / 19 | 2.6 / 8.4 / 30 | 2.5 / 11 / 30 |
| 500 | 1.8 / 20 / 58 (s) | 1.8 / 14 / 37 (s) | 1.8 / 11 / 29 | 1.8 / 17 / 50 (s) |
| 1,000 | 1.2 / 54 / 77 (s) | 1.2 / 34 / 59 (s) | 7.6 / 89 / 92 (s) | 12 / 111 / 118, behind |
| 2,000 | 30 s / 30 s / 30 s, 17,062 failed, behind | 30 s / 30 s / 30 s, 17,382 failed, behind | 5.0 s / 5.0 s / 5.0 s, 11,909 failed, behind | not run |

At 2,000/s on Jetty the failures were the client's 30-second request timeout (about 10,000) and server errors (about
6,900); on Pekko they were all the client's 5-second connect timeout, because Pekko HTTP stopped accepting new connections in time.

### Footprint

| | jetty + kotlinx | jetty + jackson | pekko + kotlinx | pekko + jackson |
|---|---:|---:|---:|---:|
| Start-up, three launches | 1.49, 1.39, 1.32 s | 1.74, 1.52, 1.54 s | 1.65, 1.54, 1.53 s | 1.92, 1.76, 1.82 s |
| CPU spent starting | 4.5 s | 5.5 s | 5.4 s | 6.2 s |
| CPU for 300,000 browses | 21.4 s | 22.5 s | 41.9 s | 43.2 s |
| Browses a second (`ab -k -c 8`) | 84,169 | 78,052 | 43,293 | 43,342 |
| Peak heap used | 159 MB | 229 MB | 168 MB | 240 MB |
| Resident memory at the end | 441 MB | 553 MB | 492 MB | 611 MB |
| Classes loaded | 10,662 | 12,193 | 13,624 | 15,121 |
| of which `org.apache.pekko` | 0 | 0 | 2,678 | 2,658 |
| of which `scala` | 0 | 0 | 731 | 730 |
| of which `org.eclipse.jetty` | 416 | 416 | 0 | 0 |
| of which `com.fasterxml.jackson` | 67 | 766 | 67 | 766 |
| of which `kotlinx.serialization` | 127 | 53 | 127 | 53 |
| Jars a class was loaded from | 69 (29 MB) | 78 (32 MB) | 75 (49 MB) | 84 (52 MB) |

Both stacks share one distribution today: `app/build/install/app/lib` holds 163 jars, 91 MB, whichever stack is
chosen. The Pekko jars and the Scala library are 14 jars and 24 MB of that; the Jetty jars are 11 jars and 2.7 MB. A
Jetty process never opens the Pekko or Scala jars, and a Pekko process never opens the Jetty ones, as the class counts
show, so the jar counts above are what each stack actually loads rather than what ships. The 67 Jackson classes on
kotlinx come from `jackson-core`, which something on the shared classpath reads at start-up.

## Reading

**The server.** Jetty answers browsing with a lower tail than Pekko HTTP at every rate above 1,000/s: 0.6 against
0.8 ms at p99 at 2,000/s, and 2 to 3 ms against 4 to 7 ms at 10,000/s and 12,000/s. Neither stopped keeping up, so the
in-process ladder shows the tail and not a ceiling. The ceiling shows out of process, where nothing else shares the
JVM: Jetty served 78,000 to 84,000 browses a second over eight connections, Pekko HTTP 43,000, and Pekko spent about
twice the CPU for the same 300,000 requests. Pekko also costs about 3,000 more classes, 20 MB more jars on the
classpath, 0.15 to 0.3 s more start-up and 50 to 60 MB more resident memory. On adoption the server matters less, because the shop's own
path is the limit, but Pekko's p50 at 1,000/s is 8 to 12 ms against Jetty's 1.2 ms. A likely contributor is that each
adoption makes two registry calls through `PekkoHttpTransport`, whose host connection pool allows few connections by
default; this run did not isolate it.

**The codec.** Jackson loads 1,500 more classes, takes 0.15 to 0.3 s more to start and peaks 70 MB higher in heap than
kotlinx on either server. Under load the two are hard to tell apart: on Jetty, Jackson's browsing tail is if anything a
little lower, and out of process kotlinx is about 8% faster on Jetty and equal on Pekko. A catalogue of 100 pets is a
small body, so the codec is a small share of each request.

**Where each stops.** Browsing: none stopped by 12,000/s in process. Adoption: all four kept up at 500/s, Jetty with
both codecs and Pekko with kotlinx kept up at 1,000/s (Pekko with 89 ms at p99, close to the 100 ms line), Pekko with
Jackson fell behind at 1,000/s with 111 ms, and every combination tried at 2,000/s failed most of its requests. The
adoption ceiling of roughly 1,000/s is the shop's, not the server's: every adoption is a message to one actor that
waits for a Postgres transaction before taking the next, and the database and the in-process registry are the same for
every combination. Browsing, which touches neither, is the scenario that shows the server.

**Caveats.** One run per combination on a laptop. The generator, the registry and the shop share one JVM in the load
test, so the in-process numbers include their contention; the footprint runs are the shop alone. The generator's
schedule slipped on most rungs above 1,000/s on every combination, so those rates arrived less evenly than planned.
