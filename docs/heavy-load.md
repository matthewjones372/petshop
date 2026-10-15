# Fifty thousand requests a second

`loadtest/heavy.sh` ramps one shop from 100 to 50,000 requests a second over a minute, then holds it there for a
minute. This page is what it found on one laptop on 2026-10-15.

## Summary

- **The shop answers 50,000 requests a second, but not cleanly, and this laptop cannot show whether it could.** In the
  best run with the final generator, the hold sent 50,000 a second and the shop answered every request it was sent.
  5.1% were refused by the generator because 1,024 were already in flight. The p99 service time over the hold was
  46 ms and the slowest request 210 ms; seconds with no refusals had a p99 of 3 to 19 ms.
- **The ceiling here is the machine's CPU, shared by the shop and its load generator.** At 50,000 a second the shop
  used about 2.6 cores and the generator about 3. Each JVM sizes its thread pools to all eight cores, so together they
  oversubscribe the machine: the load average climbed from under 8 to between 16 and 34 during each run, and how high
  it went decided how much was refused (5% of the hold at 16, 30 to 36% at 30 to 34).
- **The shop's own tail follows its garbage collection.** It allocates about 400 MB a second at this rate and pauses
  for a young collection of 15 to 40 ms every one to two seconds, and the refusals come in bursts on that rhythm.
- **Nothing else in the shop is near a limit.** Its Postgres pool never had more than one of ten connections in use
  or anyone waiting, Postgres stayed under 3% CPU, and the shop held at about 83 threads (handlers run on virtual
  threads; Jetty's pool of 200 only does I/O).
- **The load generator was the hardest part.** Three generator designs collapsed before the shop did, each in a way
  that looked like the shop failing. They are described below, because the same traps wait for any heavy load test
  here.

What would show 50,000 a second sustained is the generator on a second machine. The changes at the end would lower the
shop's GC tail and its CPU per request.

## Machine and run

- MacBook Air, Apple M3, 8 cores, 24 GB, macOS 26. Docker Desktop's VM ran Postgres and the registry stub.
- The demo's browser container was stopped; the rest of the demo stack kept running. The script waits until the
  one-minute load average is under the number of cores before it starts; it was 3.5 to 7.9 at each start.
- The shop is the installed distribution (`./gradlew :app:installDist`) with default JVM flags: G1 and the default
  heap. The generator is a second JVM with a 4 GB heap. Both are JDK 25.
- Pelican 1.0.1-SNAPSHOT, lark 0.10.2-SNAPSHOT, Proofload 0.1.1-SNAPSHOT, Jetty 12.1.13, PostgreSQL 17.

### The scenario

`loadtest/src/test/kotlin/petshop/load/HeavyTraffic.kt`. Each arrival is one visitor making one request, chosen by a
seeded roll per visitor:

- 74% `GET /pets/{id}`, a pet chosen at random from the catalogue of 100;
- 25% `GET /pets`, the whole catalogue;
- 1% `POST /pets/{id}/adoption`, each pet on the shelf in turn and then, once the shelf is empty, a random one, which
  the shop refuses as already adopted.

Arrivals are open model: they depart on the profile's schedule whether or not earlier ones have been answered. Service
time is measured from the moment a request left to the moment its answer arrived.

The client is Jetty's, with at most 1,024 requests in flight; one more fails at once as `NoFreeConnection`. At 50,000
a second, more than 1,024 in flight means the shop's answers were taking longer than about 20 ms, so a refusal says
the shop was slow at that moment. It is not an error from the shop: a real crowd of clients would have waited.

The adoption share writes to Postgres only until the shelf is empty: 99 adoptions taken in each run, then refusals.
A catalogue large enough to keep 500 adoptions a second writing would make `GET /pets` return tens of thousands of
pets, because it returns every pet the shop has ever had. The adoption ladder in [performance.md](performance.md)
measures writes alone: about 1,000 a second before the shop falls behind.

## Results with the final generator

| Run | First refusal in the ramp | Hold: refused | Hold: p99 | Hold: slowest | Load average, start to end |
|---|---:|---:|---:|---:|---|
| G1, first | 31,700/s | 36% | 113 ms | 805 ms | 7.7 to 34.8 |
| G1, second | 25,900/s | 5.1% | 46 ms | 210 ms | 7.6 to 16.0 |
| ZGC | 29,200/s | 30% | 109 ms | 604 ms | 7.9 to 30.4 |

Every refusal in all three was `NoFreeConnection`: no timeouts and no errors from the shop. ZGC's pauses are under a
millisecond, but its collector runs on threads of its own and competes for the same saturated cores, so that run
neither confirms nor rules out G1's pauses as the cause of the bursts.

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
| CPU, the shop | About 2.6 cores at 50,000/s, so about 50 µs a request. |
| GC, the shop | About 400 MB a second; a young pause of 15 to 40 ms every one to two seconds, once 248 ms. |
| The load generator | About 3 cores. Earlier designs collapsed first: see below. |
| Postgres pool | At most one of ten connections in use, never a wait. |
| Jetty threads | About 83 threads in the shop throughout. |

### Where the shop's CPU goes

From a JFR recording of the shop at 50,000/s (`jfr view hot-methods`):

- **17.5%** in lark's actor runtime. `Runners.run` removes its runner from a `ConcurrentHashMap` key set each time
  the shop actor's mailbox runs dry, and the next message starts a new virtual thread. That churn is also 13% of the
  shop's allocation, as virtual thread stack chunks.
- **9%** in kotlinx.serialization writing strings, nearly all of it encoding the 100-pet catalogue for `GET /pets`.
- Jetty's request parsing and the virtual thread scheduler take most of the rest, a few percent each.

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
at about three cores, and its slowest request in any run was 0.8 s.

## What would get it to 50,000 a second, sustained

1. **Generate the load from a second machine.** This is the one change that would show whether the shop sustains the
   rate, because it gives the shop the cores the generator takes. Proofload can split a run across injectors.
2. **Proofload: bound what a backlog costs the generator.** A cap on users in flight, with arrivals past it counted
   as not sent rather than each given a thread, would do inside the engine what the transport does here, for any
   client. A run whose generator saturated could then be reported void, as a run that ran out of ports already is.
3. **Allocate less per request.** Encoding `GET /pets` once per change to the catalogue, and serving it with an
   `ETag`, removes most of the 9% spent encoding and much of the allocation behind the GC pauses.
4. **Serve reads without the actor.** Every read is an ask to the one actor that also writes adoptions to Postgres.
   An immutable snapshot of the catalogue, swapped by the actor after each write and read directly by the handlers,
   removes the ask and its runner churn from 99% of requests, and stops a slow write from holding up reads.
5. **Shed load in the shop.** A lark `Bulkhead` around the actor ask (`guard(Bulkhead(...))` in a `policy`, with a
   `deadline`) would answer a burst past its limit with an immediate 503, rather than letting thousands of requests
   park waiting for room in the actor's mailbox.
6. **Lark: keep runners parked.** Keeping a runner, rather than retiring it each time one actor's mailbox empties,
   would save most of the 17.5%.

## Running it

```bash
./gradlew :app:installDist :loadtest:installDemoTraffic
loadtest/heavy.sh build/heavy
```

`PEAK`, `RAMP`, `HOLD`, `PETS` and `TIMEOUT` change the scenario, and `SHOP_OPTS` and `GENERATOR_OPTS` pass flags to
each JVM. The script starts Postgres on 55499, the registry stub on 8189 and the shop on 8180, so a running demo keeps
its ports, and removes all three when it exits. It waits up to ten minutes (`QUIET_WAIT` times five seconds) for the
load average to fall under the number of cores (`QUIET`), and refuses to run if it does not. It writes the HTML
report, `seconds.tsv` (each second's requests, refusals and percentiles), `samples.tsv` (each process's CPU, the
shop's threads and pool, Postgres's CPU and the sockets on the shop's port) and the shop's log.
`./gradlew :loadtest:heavyTraffic` runs the scenario alone against a shop at `PETSHOP_URL`.
