# The petshop demo

The shop running against a small production-like estate on your laptop: steady traffic, metrics, logs, traces,
alerts, a dashboard in Grafana, an operations page in [Estate](https://github.com/matthewjones372/estate), a script
that breaks things on purpose, and an optional Ask AI that explains the alerts it causes.

The application runs on the host. Everything else runs in Docker.

**Every command on this page runs from the repository root**, the directory with `gradlew` in it. Compose commands
name the file with `-f demo/docker-compose.yml`, so there is no need to `cd demo` and back.

## Quick start

You need Docker and a JDK. Two terminals.

**1. Start the stack.** The first run builds the traffic generator's image and the front end's, which takes a few
minutes. Estate's image tag `:main` moves, so pull it first:

```bash
docker compose -f demo/docker-compose.yml pull estate
```

```bash
docker compose -f demo/docker-compose.yml up -d
```

Compose goes first because the shop will not start without its database.

**2. Start the shop**, in a second terminal:

```bash
BUS=kafka LOKI_URL=http://localhost:3100 OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 HOST=0.0.0.0 ./gradlew :app:run
```

Leave it running. The traffic generator waits for the shop to answer, then starts adopting.

**3. Open the pages.**

| Page | URL | What it is for |
|---|---|---|
| Estate | <http://localhost:8095> | Start here. "Is the shop well, and if not, where do I look?": alerts, services, stores, the map, releases, Ask AI |
| The shop's front end | <http://localhost:5173> | The web app a customer uses. Click around and Estate's `petshop-web` numbers move |
| Grafana | <http://localhost:3000> | The petshop dashboard, and Explore for Tempo traces and Loki logs |
| The shop's API | <http://localhost:8080/pets> | The application itself, on the host |

Nothing asks you to log in. Grafana's dashboard is provisioned, so there is nothing to import.

### What the shop's settings do

| Setting | Without it |
|---|---|
| `BUS=kafka` | Events stay in the process. With it they go to the demo's Kafka, which gives Estate's Kafka store, the projection's lag and the `kafka-down` chaos scenario something to show |
| `LOKI_URL` | The shop logs to the console only. With it, lines also go to Loki under `app="petshop"`, which is where Estate's logs panel and Ask AI read them |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | No span leaves the process. With it, traces go to Tempo |
| `HOST=0.0.0.0` | The shop binds 127.0.0.1. Needed on Linux, harmless on a Mac (see [Linux](#linux)) |

## What is running

`docker compose -f demo/docker-compose.yml ps` lists these. The service name is what `stop`, `start` and `logs` take.

| Service | Port | What it does |
|---|---|---|
| `web` | 5173 | The front end in `web/`, built with bun and served by `vite preview`, pointed at the shop on the host |
| `traffic` | | Proofload's visitors calling the shop's API: five a second, one in four trying to adopt |
| `browser` | | Headless Chromium visiting the front end four times a minute, so `petshop-web` has numbers too |
| `estate` | 8095 | The operations page. Reads Prometheus, Alertmanager, Loki, Tempo and `tools` |
| `grafana` | 3000 | Dashboards and Explore |
| `prometheus` | 9090 | Scrapes the shop every two seconds, evaluates `prometheus/rules.yml`, takes Tempo's span metrics |
| `alertmanager` | 9093 | Receives the alerts. Estate reads them and keeps its silences here |
| `loki` | 3100 | Log lines from the shop and the front end |
| `tempo` | 3200, 4318 | Traces over OTLP on 4318, turned into span metrics and a service graph for Prometheus |
| `postgres` | 55432 | The shop's database, with the outbox in it. 55432 since a local Postgres usually holds 5432 |
| `kafka` | 9092 | The bus, when the shop runs with `BUS=kafka` |
| `schema-registry` | 8081 | The Avro schema of `petshop.events` |
| `registry` | 8089 | A WireMock stand-in for the chip registry the shop calls on every adoption |
| `tools` | 8096 | A stand-in for GitHub Actions, Argo CD and Kubernetes, so Estate has releases to show |
| `postgres-exporter`, `kafka-exporter` | | Metrics for the two stores |

## Everyday commands

```bash
docker compose -f demo/docker-compose.yml ps                       # what is running
docker compose -f demo/docker-compose.yml logs -f estate           # follow one service's logs
docker compose -f demo/docker-compose.yml stop traffic browser     # quiet the traffic
docker compose -f demo/docker-compose.yml start traffic browser    # and bring it back
docker compose -f demo/docker-compose.yml up -d --build web        # rebuild the front end after changing web/
docker compose -f demo/docker-compose.yml down                     # stop everything, keep the data
docker compose -f demo/docker-compose.yml down -v                  # stop everything, and empty the shop
```

The demo's Postgres keeps its data between runs, so pets and adoptions pile up. `down -v` then `up -d` starts from
an empty shop.

If `bun run --cwd web dev` already holds 5173, serve the demo's front end on another port:

```bash
WEB_PORT=5175 docker compose -f demo/docker-compose.yml up -d
```

## Traffic

The `traffic` service runs Proofload's visitors against the shop on the host, starting once the shop answers and
following it across restarts. Each visitor looks at a pet and the stats, and one in four tries to adopt. The
adoptions land on every outcome the shop declares (taken, already adopted, not chipped, no such pet), so Estate and
Grafana show refusals as well as sales.

Those visitors call the API, so the front end hears nothing of them. The `browser` service is the front end's
traffic: headless Chromium (Playwright's image, `browser/Dockerfile`) opening <http://web:5173>. Each visitor loads
`/pets`, opens a pet, adopts it one time in four, then watches `/live` and `/stats` for a few seconds before leaving.
Every visit is a fresh page load, so it reports its own Web Vitals, and Estate's `petshop-web` and the map's edge to
the shop move with nobody at the keyboard.

### Heavy traffic

The `heavy` override raises it to fifty API visitors a second and sixteen browser visits a minute. Most of the extra
adoptions are `already_adopted`, since a pet arrives only every five seconds.

```bash
docker compose -f demo/docker-compose.yml -f demo/docker-compose.heavy.yml up -d
```

`up -d` without the override goes back to the usual pace.

### The same visitors from Gradle

Instead of the `traffic` service, for half an hour by default. `RATE`, `DURATION` (`24h`) and `PETSHOP_URL` change
how many, for how long, and where. The report is written to `loadtest/build/reports/proofload/demo-traffic.html`.

```bash
./gradlew :loadtest:demoTraffic
```

### By hand

Stop the traffic first, or it may adopt the pet you picked, and pick a pet that `curl localhost:8080/pets` shows with
`"adopted": false`:

```bash
docker compose -f demo/docker-compose.yml stop traffic
PET=15                                                 # a pet nobody has adopted yet
curl -X POST localhost:8080/pets/$PET/adoption         # taken
curl -X POST localhost:8080/pets/$PET/adoption         # already_adopted
curl -X POST localhost:8080/pets/999999999/adoption    # no_such_pet
curl -X POST localhost:8080/pets/3/adoption            # not_chipped: the registry has no chip for Mrs Peel
docker compose -f demo/docker-compose.yml stop registry
curl -X POST localhost:8080/pets/16/adoption           # registry_down, and pet 16 stays in the shop
docker compose -f demo/docker-compose.yml start registry traffic
```

Arrivals happen on their own, every `petshop.arrivalsEvery`.

## Breaking things

`demo/chaos.sh` breaks one part of the demo at a time, so Estate has an alert to show. It prints which alert to
expect, and what a useful Ask AI answer would name. It works from any directory.

**Before you start**, check all three, or the alerts will not fire:

1. The stack is up: `docker compose -f demo/docker-compose.yml ps` shows `traffic` running.
2. The shop is running on the host with the settings from the [quick start](#quick-start). `kafka-down` needs
   `BUS=kafka`, and Ask AI needs `LOKI_URL` to see the shop's logs.
3. Adoptions are happening: Estate's **Adoptions** vital, at the top of its page, is above zero. Most alerts fire on failed
   adoptions, so with the traffic stopped, only `postgres-down` and `kafka-down` show anything.

Then break one thing:

```bash
demo/chaos.sh registry-down
```

| Scenario | What it breaks | Alert in Estate, and how soon |
|---|---|---|
| `registry-down` | stops the chip registry | `ChipRegistryUnreachable` within a minute, as adoptions fail with `registry_down` |
| `registry-slow` | the registry answers after 3s, past the shop's 2s timeout | `ChipRegistryUnreachable` within a minute, though the registry is up: a subtler cause for Ask AI |
| `postgres-down` | stops Postgres | `PostgresDown` in about 15s, then `PetshopNotReady` naming the database check |
| `kafka-down` | stops Kafka | `KafkaDown` in about 30s, and the outbox's rows climb on the Postgres store |

Open Estate at <http://localhost:8095>. The alert arrives at the top of the page; open it to read what it means, add
a note, silence it, or press Ask AI.

The fault stays until you put it back:

```bash
demo/chaos.sh restore
```

Or give a number of seconds, and the script puts it back itself (Ctrl-C to leave it broken):

```bash
demo/chaos.sh registry-slow 120
```

`ChipRegistryUnreachable`, `AdoptionsNotRecorded` and `BusRefusingEvents` keep firing for five minutes after their
cause stops, so there is time to read the page and ask about it after `restore`.

`demo/chaos.sh` with no scenario prints its usage.

## Ask AI

Estate's Ask AI reads an alert's brief (the alert, the shop's stats, its logs in Loki, what is around it) and asks
Claude for the likely cause and what to do. It is off by default, and its button is hidden until it is on.

**1. Get an API key** from <https://console.anthropic.com>.

**2. Put it in the shell** that will run Compose. This prompts for it and keeps it out of your shell history (works
in zsh and bash):

```bash
printf 'Anthropic API key: '; read -rs ANTHROPIC_API_KEY; echo; export ANTHROPIC_API_KEY
```

**3. Restart Estate with the `ai` override:**

```bash
docker compose -f demo/docker-compose.yml -f demo/docker-compose.ai.yml up -d
```

Only Estate is recreated. The override points it at `estate/estate-ai.yaml`, which is `estate.yaml` with `ai:` added:
Claude Haiku, capped at 150,000 tokens a day.

**4. Check it is on:**

```bash
docker compose -f demo/docker-compose.yml exec estate printenv ESTATE_SETTINGS
```

`/etc/estate/estate-ai.yaml` means it is on; `/etc/estate/estate.yaml` means it is not.

**5. Give it something to explain.** Run a [chaos scenario](#breaking-things), wait for the alert in Estate, open
it, and press Ask AI. `registry-slow` is the most interesting: the registry is up, so a good answer has to notice the
calls are timing out rather than refused.

### When Ask AI does not appear

- **`up` fails with "set ANTHROPIC_API_KEY to try Ask AI".** The key is not exported in this shell. Do step 2 again in
  the same terminal as step 3.
- **The check in step 4 says `estate.yaml`.** Estate was recreated without the override. Any `up -d` that names only
  `demo/docker-compose.yml` turns Ask AI off again, including the heavy traffic one. Name all the overrides you want
  together:

  ```bash
  docker compose -f demo/docker-compose.yml -f demo/docker-compose.ai.yml -f demo/docker-compose.heavy.yml up -d
  ```

- **The button is there but answers fail.** `docker compose -f demo/docker-compose.yml logs estate` says why: usually
  a mistyped key, or the day's token budget spent.
- **The answer knows nothing about the shop's logs.** The shop was started without `LOKI_URL`.

`stop`, `start`, `logs` and `ps` do not need the override named, and `demo/chaos.sh` leaves Estate alone, so neither
turns Ask AI off.

## Estate

[Estate](https://github.com/matthewjones372/estate) answers "is the shop well, and if not, where do I look?" on one
page, at <http://localhost:8095>. It reads the same Prometheus as Grafana, and the Alertmanager that
`prometheus/rules.yml` fires into, so an alert arrives at the top of the page with what it means for adopters, a
place for notes, and a silence that Alertmanager keeps.

The shop's page carries its JVM (heap, GC pauses, threads, CPU), its connection pool, and how many health checks
are failing. The Postgres store shows connections, transactions, size and the events waiting in the outbox. The
Kafka store shows the topic's offsets and the projection's lag.

The demo has no Kubernetes, Argo CD or CI, so the `tools` service pretends to be all three (`tools/fake.ts`), and
Estate shows releases as it would for a shop that had them. Every nine minutes the shop has a new build on GitHub
Actions, named for a plausible commit; two minutes later Argo CD applies it and its two pods roll to the new image,
`main-<n>-<sha>`. The front end does the same every fourteen minutes, with one pod. So the cards carry a running
version and a rail, the feed reads "build passed" and then "main-199-26d8c47 applied", and an alert that fires soon
after a release says so. None of it is real: the shop is still the process on your machine, whatever version the
card says. There is no p99 for the shop either: the adoption timer is a summary without percentiles.

Estate reads the adoption's traces too. The catalog names it as a journey, *Adoption, click to screen*, with a stage
for each step from the click to `live event`, and on the `petshop` and `petshop-web` pages *Show where its time goes*
reads a sample of the last 15 minutes' adoptions from Tempo: each stage's p50 and p99, the largest marked, and a
typical adoption drawn in lanes, one for each of the browser, the shop, the chip registry, Postgres and Kafka. Most of
an adoption is usually *Waits in the outbox*, close to half of `outboxEvery`, since the relay polls. Each example
opens its own page, where the same trace is also a waterfall.

## Traces

An adoption from the front end is one trace in Tempo, from the click to the page that shows it:

```
adopt pet                         petshop-web   the click
  POST /pets/{petId}/adoption     petshop-web   the browser's call, which sends traceparent
    POST /pets/{petId}/adoption   petshop       the shop's request span
      adopt                       petshop
        INSERT outbox             petshop       the pet and its event, in one transaction
          publish                 petshop       the relay, up to outboxEvery later
            process               petshop       the projection, after Kafka
              live event          petshop-web   /live showing it
        GET, POST                 petshop       the calls to the chip registry
```

The trace crosses the outbox because the write keeps what it was made in (lark's `Carriers.capture()`) in the
row's `metadata` column, and the relay publishes each event inside it. On Kafka it rides as record headers, and the
projection handles each record inside them. `/events` hands each event's `traceparent` to the page, which opens
`live event` under it. An arrival is a trace of its own, `arrival`, that ends the same way.

Open Grafana's Explore at <http://localhost:3000/explore>, pick **Tempo**, and search for service `petshop-web` or
`petshop`, or run the TraceQL `{ name = "adopt pet" } && { name = "live event" }` for adoptions someone watched
arrive.

The shop's log lines about an adoption carry its `trace_id`. In Explore with **Loki**, `{app="petshop"} | trace_id != ""`
lists them, and each line links to its trace.

The front end in `web/` traces itself as service `petshop-web`. Tempo turns every span into span metrics and a
service graph, written to Prometheus, and that is how Estate shows `petshop-web`: its calls, errors and p99, the
adoption's click-to-answer time and `/live`'s reconnects from the span metrics, the edge to the shop from the service
graph, and the latest Core Web Vitals from the browser's own OTLP metrics. Its lines (errors, vitals, reconnects) go to
Loki as `{service_name="petshop-web"}`.

## On Kafka

With `BUS=kafka`, every event the relay publishes is an Avro record on `petshop.events`, and the projection behind
`/stats` reads it back from there. Its schema is in the registry under `petshop.events-value`:

```bash
curl localhost:8081/subjects/petshop.events-value/versions/latest
```

## The outbox panels

The bottom row of Grafana's dashboard is the relay. **Published and refused per second** is what it carried from the
outbox table to the bus, and what the bus turned away. A refused event stays in the table and goes again on a later
tick, so refusals with no dip in published are the bus pushing back, not events lost. `refused` reads 0 rather than
nothing: the counter does not exist until the first refusal.

**Claimed per tick** is how many rows the last claim took. A claim takes at most 100, the batch, and the panel draws a
red line there: a line sitting on it means the table is filling faster than a tick drains it. An adoption the shop
could not write to the table at all shows in the adoptions panel as `not_recorded`.

## Two things the panels show

**`outcome` is a label, not three metric names.** One counter answers "how many
adoptions" and "how many were refused" because the outcome is a tag:
`sum by (outcome) (rate(petshop_adoptions_total[1m]))`.

Every branch tags the same key and only that key. Prometheus requires one set of
label names per metric name, and a series registered with a different set is
dropped silently. That is how the first version of this lost every refusal while
appearing to work.

**The durations are seconds.** `timed` records in seconds, Prometheus's
convention, though the name has no `_seconds` suffix:
`petshop_adopt_duration_sum` is seconds. The panels say `s`, and Estate's
mean adoption multiplies by 1000 to show milliseconds.

## Linux

On Linux, `host.docker.internal` is the Docker bridge (`host-gateway`, usually 172.17.0.1) rather than the host's
loopback. The shop binds 127.0.0.1 unless told otherwise, so Prometheus's scrape is refused and every panel stays
empty. `HOST=0.0.0.0` binds all interfaces and lets the scrape in. It also serves the shop to your network, which is
why it is not the default. Docker Desktop on a Mac or Windows forwards `host.docker.internal` to loopback, so there
it is harmless either way.

`extra_hosts: host.docker.internal:host-gateway` in the compose file is there for the same reason: Linux has no such
name otherwise. Docker Desktop ignores it.

## What is where

| File | What it is |
|---|---|
| `docker-compose.yml` | every service in [What is running](#what-is-running). The shop itself runs on the host |
| `docker-compose.ai.yml` | an override that turns on Estate's Ask AI and passes `ANTHROPIC_API_KEY` through |
| `docker-compose.heavy.yml` | an override that raises the traffic and the browser's visits |
| `chaos.sh` | breaks the registry, Postgres or Kafka, one at a time, and says which alert to look at |
| `traffic/Dockerfile` | Proofload's visitors, the `traffic` service |
| `browser/Dockerfile` | the headless browser visiting the front end |
| `web/Dockerfile` | the front end in `web/`, built with bun and served by `vite preview` through the same proxies as `bun run dev` |
| `tempo/tempo.yaml` | Tempo, receiving OTLP over HTTP on 4318, and writing span metrics and the service graph to Prometheus |
| `registry/mappings/` | what the stand-in registry answers: a chip for every pet but number 3, written from the registry's endpoints by `DemoStubsSpec` |
| `prometheus/prometheus.yml` | scrapes `host.docker.internal:8080/metrics` every two seconds, and the Postgres and Kafka exporters. It also takes Tempo's remote writes and the front end's OTLP metrics |
| `prometheus/rules.yml` | the alerts: the shop's keep `job="petshop"`, which is how Estate knows an alert is the petshop's, the front end's keep `service="petshop-web"`, and the stores' carry `store:` naming theirs |
| `alertmanager/alertmanager.yml` | routes every alert nowhere: Estate reads them, and keeps its silences there |
| `grafana/provisioning/` | the Prometheus, Tempo and Loki datasources, Loki's lines linking to Tempo by `trace_id`, and the dashboard provider |
| `grafana/dashboards/petshop.json` | the dashboard itself |
| `estate/catalog.yaml` | the petshop and its front end as two services in one environment, with its Postgres and Kafka as stores: the shop's load from every request it served, adoptions as their own rate, its JVM and pool, the front end's from Tempo's span metrics, the stores' stats, the vitals, the map, and the adoption as a journey through them |
| `estate/estate.yaml` | Estate's settings: no sign-in (every visitor is an operator), the demo's Prometheus, Alertmanager, Loki and Tempo as its sources, and `tools` as its cluster, Argo CD and GitHub Actions |
| `estate/estate-ai.yaml` | `estate.yaml` with Ask AI turned on, read when Compose is started with `docker-compose.ai.yml` |
| `tools/fake.ts` | the stand-in for GitHub Actions, Argo CD and Kubernetes: a release of each service on a cadence of its own, worked out from the clock |

## Anonymous admin

Grafana runs with the login form off and anonymous access as Admin, and Estate
lets every visitor in as an operator, who may silence alerts, with a session
secret written in `estate/estate.yaml`. That is fine for something on a laptop
for ten minutes and is not fine anywhere else.
