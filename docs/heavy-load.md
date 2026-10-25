# Fifty thousand requests a second

`loadtest/heavy.sh` ramps one shop from 100 to 50,000 requests a second over a minute, then holds it there for a
minute. This page is what it found on one laptop on 2026-10-15: first with that morning's surefold and Proofload, then
again that evening with surefold's parked runners (surefold #421) and Proofload's cap on users in flight (Proofload #126 to
#128).

## Summary

- **The shop answers 50,000 requests a second, but not cleanly, and this laptop cannot show whether it could.** In the
  best run, with surefold's parked runners and Proofload's cap, the shop answered 48,800 a second through the hold with
  no failures, and the generator refused 2.4% of arrivals because 1,024 were already in flight. The p99 service time
  over the hold was 36 ms and the slowest request 185 ms. Proofload reads every run void, because every run refused
  some arrivals.
- **The ceiling here is the machine's CPU, shared by the shop and its load generator.** At 50,000 a second the shop
  used about 2.7 cores and the generator about 3. Each JVM sizes its thread pools to all eight cores, so together they
  oversubscribe the machine, and the load average climbed from under 8 to between 16 and 35 during each run. The
  share refused in the hold ranged from 2.4% to 14% across four runs of the same code, and nothing measured in the
  shop explains the difference.
- **Garbage collection is not what sets the tail.** With a GC log and nothing else recording, the shop's young pauses
  averaged 1.3 ms and the longest was 11 ms. A refusal needs the shop's answers to take more than about 20 ms, so the
  bursts of refusals are not GC pauses. The 15 to 40 ms pauses this page first reported came from a run that was also
  recording with JFR's profiling settings while its generator collapsed.
- **Surefold's parked runners moved its cost rather than removing it.** Surefold's actor runtime took 17.5% of the shop's CPU
  before and about 15% after, and the shop's CPU per request is unchanged at about 55 µs.
- **Nothing else in the shop is near a limit.** Its Postgres pool never had more than one of ten connections in use
  or anyone waiting, Postgres stayed under 3% CPU, and the shop held at about 83 threads (handlers run on virtual
  threads; Jetty's pool of 200 only does I/O).
- **The load generator was the hardest part.** Three generator designs collapsed before the shop did, each in a way
  that looked like the shop failing. They are described below, because the same traps wait for any heavy load test
  here.

What would show 50,000 a second sustained is the generator on a second machine. The changes at the end would lower the
shop's CPU per request.

## Machine and run

- MacBook Air, Apple M3, 8 cores, 24 GB, macOS 26. Docker Desktop's VM ran Postgres and the registry stub.
- The demo's browser container was stopped; the rest of the demo stack kept running. The script waits until the
  one-minute load average is under the number of cores before it starts; it was 3.5 to 7.9 at each start.
- The shop is the installed distribution (`./gradlew :app:installDist`) with default JVM flags: G1 and the default
  heap. The generator is a second JVM with a 4 GB heap. Both are JDK 25.
- Pelican 1.0.1-SNAPSHOT, surefold 0.10.2-SNAPSHOT, Proofload 0.1.1-SNAPSHOT, Jetty 12.1.13, PostgreSQL 17.

### The scenario

`loadtest/src/test/kotlin/petshop/load/HeavyTraffic.kt`. Each arrival is one visitor making one request, chosen by a
seeded roll per visitor:

- 74% `GET /pets/{id}`, a pet chosen at random from the catalogue of 100;
- 25% `GET /pets`, the whole catalogue;
- 1% `POST /pets/{id}/adoption`, each pet on the shelf in turn and then, once the shelf is empty, a random one, which
  the shop refuses as already adopted.

Arrivals are open model: they depart on the profile's schedule whether or not earlier ones have been answered. Service
time is measured from the moment a request left to the moment its answer arrived.

The simulation lets at most 1,024 visitors be in flight (`inFlightAtMost(CONNECTIONS)`), and Jetty's client has a
connection for each. An arrival due while 1,024 are in flight is refused by Proofload: never sent, counted in
`result.refused` and in the `refused` column of `seconds.tsv`, and kept apart from the shop's failures. The report and
summary show it as "not sent: generator saturated", and a run that refused any arrival reads void
(`result.generatorSaturated()`), so it does not count as the shop sustaining the rate or failing it. At 50,000 a second,
more than 1,024 in flight means the shop's answers were taking longer than about 20 ms, so a refusal says the shop was
slow at that moment, but it is not an error from the shop: a real crowd of clients would have waited.

The adoption share writes to Postgres only until the shelf is empty: 99 adoptions taken in each run, then refusals.
A catalogue large enough to keep 500 adoptions a second writing would make `GET /pets` return tens of thousands of
pets, because it returns every pet the shop has ever had. The adoption ladder in [performance.md](performance.md)
measures writes alone: about 1,000 a second before the shop falls behind.

## Results with surefold's parked runners and Proofload's cap

The surefold snapshot was published from surefold's main at 066eb9c, after #421, and the Proofload snapshot after #128. The
fourth run also recorded the shop with JFR's default settings and a GC log.

| Run | First refusal in the ramp | Hold: answered a second | Hold: refused | Hold: p99 | Hold: slowest | Load average, start to end |
|---|---:|---:|---:|---:|---:|---|
| First | 35,000/s | 43,100 | 13.8% | 76 ms | 470 ms | 6.9 to 19.0 |
| Second | 20,900/s | 48,800 | 2.4% | 36 ms | 185 ms | 7.6 to 19.3 |
| Third | 38,400/s | 46,500 | 7.1% | 50 ms | 285 ms | 7.7 to 16.4 |
| With JFR | 27,500/s | 44,400 | 11.2% | 59 ms | 470 ms | 7.9 to 25.7 |

No request failed in any of the four: every shortfall was an arrival Proofload refused at the cap. In the hold the shop
used 2.2 to 2.7 cores and the generator 2.6 to 3.1. The Postgres pool never had more than one connection in use, and
the shop never had more than 82 threads.

## Earlier results

| Run | First refusal in the ramp | Hold: refused | Hold: p99 | Hold: slowest | Load average, start to end |
|---|---:|---:|---:|---:|---|
| G1, first | 31,700/s | 36% | 113 ms | 805 ms | 7.7 to 34.8 |
| G1, second | 25,900/s | 5.1% | 46 ms | 210 ms | 7.6 to 16.0 |
| ZGC | 29,200/s | 30% | 109 ms | 604 ms | 7.9 to 30.4 |

These runs, from before surefold #421, predate Proofload's cap: the transport refused with a semaphore of its own, and each refusal was counted
as a failed request named `NoFreeConnection`. Every failure in all three was one: no timeouts and no errors from the
shop. With the cap they would be counted as refused, and all three runs would read void. ZGC's pauses are under a
millisecond, but its collector runs on threads of its own and competes for the same saturated cores, so that run
neither confirmed nor ruled out G1's pauses as the cause of the bursts; the GC log in the later runs did.

The hold in the second G1 run, a second at a time:

| Second | Answered | Refused | p50 ms | p99 ms |
|---:|---:|---:|---:|---:|
| 66 | 49,998 | 2,289 | 0.31 | 38 |
| 70 | 49,313 | 17,110 | 5.8 | 96 |
| 74 | 49,996 | 0 | 0.16 | 6.8 |
| 76 | 50,000 | 0 | 0.16 | 4.2 |
| 82 | 50,000 | 660 | 0.16 | 21 |
| 86 | 50,018 | 0 | 0.15 | 4.1 |
| 92 | 49,689 | 18,178 | 10 | 76 |
| 96 | 50,000 | 0 | 0.15 | 3.0 |

### Where it saturates

| Resource | What the runs showed |
|---|---|
| CPU, the machine | The limit. Shop and generator together oversubscribe eight cores; the load average passes 16. |
| CPU, the shop | About 2.7 cores at 48,800/s, so about 55 µs a request, before and after surefold #421. |
| GC, the shop | About 400 MB a second; young pauses averaged 1.3 ms, the longest 11 ms. |
| The load generator | About 3 cores. Earlier designs collapsed first: see below. |
| Postgres pool | At most one of ten connections in use, never a wait. |
| Jetty threads | About 83 threads in the shop throughout. |

### Where the shop's CPU goes

From JFR recordings of the shop at 50,000/s (`jfr view hot-methods`):

- **Surefold's actor runtime.** Before surefold #421, 17.5%: `Runners.run` removed its runner from a `ConcurrentHashMap` key
  set each time the shop actor's mailbox ran dry, and the next message started a new virtual thread. After it, about
  15%: a runner that runs out of work pushes itself onto one shared stack of parked runners (`Runners.push`, 10.7%,
  a compare-and-set loop on a single head that many runners contend for) after checking that both queues are empty
  (4.2%). Virtual thread stack chunks are 13% of the shop's allocation either way.
- **9 to 11%** in kotlinx.serialization writing strings, nearly all of it encoding the 100-pet catalogue for
  `GET /pets`.
- Jetty's request parsing and locking and the virtual thread scheduler take most of the rest, a few percent each.

## Generators that failed first

Each of these made the shop look broken while it sat idle. Thread dumps of both processes, and a direct
`curl /pets/1` every two seconds through the hold, told them apart: the shop answered every direct request in 1 to
5 ms while the generator was stuck.

1. **The JDK's `HttpClient`** opens a connection per request in flight. When the shop fell behind at about 22,000/s
   it opened 24,000 sockets and used all 16,383 of macOS's ephemeral ports. The shop's own connections to Postgres
   and the registry then failed, the actor blocked on a database read, and 8,000 request threads parked waiting for
   room in its mailbox. That run answered 2,000 a second from then on.
2. **Jetty's client with a request queue** held 50,000/s for about 40 seconds, with a p99 mostly under 35 ms and two
   short bursts of failures, then collapsed.
   With 2,048 connections, 42% of the generator's CPU went on linear scans in Jetty's connection and buffer pools.
   With 256 connections and unpooled buffers, a blip still left 120,000 requests waiting on the one lock that guards
   the queue, each timing out and removing itself by walking it. The generator took five cores and then its whole
   heap, while the shop fell to 10% CPU.
3. **A semaphore with a 50 ms wait** in front of Jetty did the same: 40,000 timed waiters, each unlinking itself from
   the semaphore's queue on timeout, kept all eight carrier threads busy, and the requests that held permits never got
   a carrier to finish on.

The final generator never waits for a slot: a request either gets one of 1,024 at once or is refused. Its CPU stayed
at about three cores, and its slowest request in any run was 0.8 s. Proofload now does this in the engine, with
`inFlightAtMost`, so a refused arrival never gets a virtual thread at all.

## What would get it to 50,000 a second, sustained

1. **Generate the load from a second machine.** This is the one change that would show whether the shop sustains the
   rate, because it gives the shop the cores the generator takes. Proofload can split a run across injectors.
2. **Proofload: bound what a backlog costs the generator.** Done: `inFlightAtMost` caps users in flight inside the
   engine, counts arrivals past it as not sent rather than giving each a thread, and reports a run that refused any as
   void. The scenario uses it in place of the transport's own semaphore.
3. **Allocate less per request.** Encoding `GET /pets` once per change to the catalogue, and serving it with an
   `ETag`, removes most of the 9% spent encoding and much of the allocation behind the GC pauses.
4. **Serve reads without the actor.** Every read is an ask to the one actor that also writes adoptions to Postgres.
   An immutable snapshot of the catalogue, swapped by the actor after each write and read directly by the handlers,
   removes the ask and its runner churn from 99% of requests, and stops a slow write from holding up reads.
5. **Shed load in the shop.** A surefold `Bulkhead` around the actor ask (`guard(Bulkhead(...))` in a `policy`, with a
   `deadline`) would answer a burst past its limit with an immediate 503, rather than letting thousands of requests
   park waiting for room in the actor's mailbox.
6. **Surefold: park runners without contending.** Surefold #421 keeps runners parked instead of retiring them, but every one
   that parks pushes onto the same stack, and at this rate that compare-and-set loop is 10.7% of the shop's CPU. A
   runner that spins briefly before parking, or a stack per carrier thread, might avoid most of it; neither is measured.

## Running it

```bash
./gradlew :app:installDist :loadtest:installDemoTraffic
loadtest/heavy.sh build/heavy
```

`PEAK`, `RAMP`, `HOLD`, `PETS` and `TIMEOUT` change the scenario, and `SHOP_OPTS` and `GENERATOR_OPTS` pass flags to
each JVM. The script starts Postgres on 55499, the registry stub on 8189 and the shop on 8180, so a running demo keeps
its ports, and removes all three when it exits. It waits up to ten minutes (`QUIET_WAIT` times five seconds) for the
load average to fall under the number of cores (`QUIET`), and refuses to run if it does not. It writes the HTML
report, `seconds.tsv` (each second's requests, failures, arrivals refused at the cap and percentiles), `samples.tsv` (each process's CPU, the
shop's threads and pool, Postgres's CPU and the sockets on the shop's port) and the shop's log.
`./gradlew :loadtest:heavyTraffic` runs the scenario alone against a shop at `PETSHOP_URL`.
