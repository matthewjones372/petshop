# Watching the numbers

Two terminals. The application runs on the host. Everything else runs in
Docker: the front end, Prometheus, Grafana, Alertmanager, Estate, Loki, Tempo, a traffic generator, the Postgres the outbox is in,
Kafka and its schema registry, an exporter for each of those two, and a stand-in
chip registry. Compose goes first,
because the application will not start without its database. The first `up` builds the traffic generator's image
and the front end's, which takes a few minutes.

```bash
cd demo && docker compose up -d
```

From the repository root:

```bash
BUS=kafka LOKI_URL=http://localhost:3100 OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 HOST=0.0.0.0 ./gradlew :app:run
```

The demo's Postgres keeps its data between runs, so pets and adoptions pile up. To start from an empty shop:

```bash
docker compose down -v && docker compose up -d
```

`BUS=kafka` puts the shop's events on the demo's Kafka, which is what gives Estate's
Kafka store and the projection's lag something to show. Without it the events stay
in the process. `LOKI_URL` sends the shop's log lines to the demo's Loki as well as the
console, under `app="petshop"`, which is where Estate's logs panel reads them; without it
the shop logs to the console only. `OTEL_EXPORTER_OTLP_ENDPOINT` sends the shop's traces to the demo's Tempo; without
it no span leaves the process.

`HOST=0.0.0.0` is for Linux. There, `host.docker.internal` is the Docker bridge
(`host-gateway`, usually 172.17.0.1) rather than the host's loopback. The app
binds 127.0.0.1 unless told otherwise, so Prometheus's scrape is refused and
every panel stays empty. Binding all interfaces lets the scrape in. It also
serves the shop to your network, which is why it is not the default. Docker
Desktop on a Mac or Windows forwards `host.docker.internal` to loopback, so
there it is harmless either way.

The shop's front end is at <http://localhost:5173>, built from `web/` and served by compose's `web` service, which
points it at the shop on the host and the browser's spans, lines and metrics at Tempo, Loki and Prometheus. If
`bun run --cwd web dev` already holds 5173, `WEB_PORT=5175 docker compose up -d` serves it on 5175 instead. After
changing `web/`, `docker compose up -d --build web` rebuilds it.

Then open Grafana at <http://localhost:3000>, or Estate at
<http://localhost:8095> (see [Estate](#estate) below). Grafana's dashboard is
provisioned, so there is nothing to import and nothing to log into.

Compose also brings steady traffic: its `traffic` service runs Proofload's
visitors against the shop on the host, starting once the shop answers and
following it across restarts. `docker compose stop traffic` quiets it. The same
visitors run from Gradle, for half an hour by default:

```bash
./gradlew :loadtest:demoTraffic
```

Five visitors a second, each looking at a pet and the stats, and one in four
trying to adopt. The adoptions land on every outcome the shop
declares (taken, already adopted, not chipped, no such pet), so Estate and
Grafana show refusals as well as sales. `RATE`, `DURATION` (`10m`) and
`PETSHOP_URL` change how many, for how long, and where. Proofload's report is
written to `loadtest/build/reports/proofload/demo-traffic.html`.

Or make something happen by hand. Stop the traffic first, or it may adopt the pet you picked, and pick a pet that
`curl localhost:8080/pets` shows with `"adopted": false`:

```bash
docker compose stop traffic
PET=15                                                 # a pet nobody has adopted yet
curl -X POST localhost:8080/pets/$PET/adoption         # taken
curl -X POST localhost:8080/pets/$PET/adoption         # already_adopted
curl -X POST localhost:8080/pets/999999999/adoption    # no_such_pet
curl -X POST localhost:8080/pets/3/adoption            # not_chipped: the registry has no chip for Mrs Peel
docker compose stop registry
curl -X POST localhost:8080/pets/16/adoption           # registry_down, and pet 16 stays in the shop
docker compose start registry traffic
```

Arrivals happen on their own, every `petshop.arrivalsEvery`.

## Traces

Every request is a trace in Tempo: the request's span, the `adopt` span inside it, and inside that the calls to the
chip registry, which carry `traceparent` to it. Open Grafana's Explore at <http://localhost:3000/explore>, pick
**Tempo**, and search for service `petshop`.

The shop's log lines about an adoption carry its `trace_id`. In Explore with **Loki**, `{app="petshop"} | trace_id != ""`
lists them, and each line links to its trace.

The front end in `web/` traces itself too, as service `petshop-web`: a browser span for each call to the shop sits
above the shop's own, so an adoption is one trace from the click. Tempo turns every span into span metrics and a
service graph, written to Prometheus, and that is how Estate shows `petshop-web`: its calls, errors and p99, the
adoption's click-to-answer time and `/live`'s reconnects from the span metrics, the edge to the shop from the service
graph, and the latest Core Web Vitals from the browser's own OTLP metrics. Its lines (errors, vitals, reconnects) go to
Loki as `{service_name="petshop-web"}`. With the shop running as above, click about the front end at
<http://localhost:5173> and the numbers appear on Estate within a minute. The traffic generator calls the API
directly, so `petshop-web` is quiet while nobody has the front end open; its `/stats` page asks every three seconds,
which is enough to keep it moving.

## On Kafka

Compose also runs a Kafka broker on 9092 and a schema registry on 8081. The
shop uses them when it is started with `BUS=kafka`:

```bash
BUS=kafka HOST=0.0.0.0 ./gradlew :app:run
```

Every event the relay publishes is then an Avro record on `petshop.events`, and
the projection behind `/stats` reads it back from there. Its schema is in the
registry under `petshop.events-value`:

```bash
curl localhost:8081/subjects/petshop.events-value/versions/latest
```

## Estate

[Estate](https://github.com/matthewjones372/estate) answers "is the shop well, and if not, where do I look?" on one
page, at <http://localhost:8095>. It reads the same Prometheus as Grafana, and the Alertmanager that
`prometheus/rules.yml` fires into, so an alert arrives at the top of the page with what it means for adopters, a
place for notes, and a silence that Alertmanager keeps.

`:main` moves, and Docker keeps whatever it pulled last, so pull it before the first start:

```bash
docker compose pull estate
```

To see an alert, stop the registry and adopt something; `ChipRegistryUnreachable` fires on the next evaluation, and
resolves once the registry is back:

```bash
docker compose stop registry
curl -X POST localhost:8080/pets/16/adoption   # registry_down: any pet nobody has adopted
docker compose start registry
```

Stop the database and two alerts fire within about ten seconds, with nobody adopting anything:
`PostgresDown`, from the exporter's `pg_up`, on the Postgres store and the map's edge to it, and `PetshopNotReady`,
from the shop's own health check, whose `database` check stops passing. Stop Kafka and `KafkaDown` fires on the
Kafka store.

```bash
docker compose stop postgres
docker compose start postgres
```

The shop's page carries its JVM (heap, GC pauses, threads, CPU), its connection pool, and how many health checks
are failing. The Postgres store shows connections, transactions, size and the events waiting in the outbox. The
Kafka store shows the topic's offsets and the projection's lag.

The demo has no Kubernetes, Flux or CI, and Estate says so at the top of the page rather than leaving those parts
blank. For the same reason it shows the shop as "not running": it reads that from a cluster or ECS, and the shop
here is a process on your machine. There is no p99 either: the adoption timer is a summary without percentiles.

| | |
|---|---|
| `estate/catalog.yaml` | the petshop and its front end as two services in one environment, with its Postgres and Kafka as stores: the shop's load from adoptions, its JVM and pool, the front end's from Tempo's span metrics, the stores' stats, the vitals and the map |
| `estate/estate.yaml` | Estate's settings: no sign-in (every visitor is an operator), and the demo's Prometheus and Alertmanager as its sources |
| `prometheus/rules.yml` | the alerts: the shop's keep `job="petshop"`, which is how Estate knows an alert is the petshop's, the front end's keep `service="petshop-web"`, and the stores' carry `store:` naming theirs |
| `alertmanager/alertmanager.yml` | routes every alert nowhere: Estate reads them, and keeps its silences there |

## What is where

| | |
|---|---|
| `docker-compose.yml` | the front end, Prometheus, Alertmanager, Grafana, Estate, Loki, Tempo, Proofload's visitors (`traffic/Dockerfile`), the outbox's Postgres, Kafka and its schema registry, and a WireMock stand-in for the chip registry. The app itself runs on the host |
| `web/Dockerfile` | the front end in `web/`, built with bun and served by `vite preview` through the same proxies as `bun run dev` |
| `tempo/tempo.yaml` | Tempo, receiving OTLP over HTTP on 4318, and writing span metrics and the service graph to Prometheus |
| `registry/mappings/` | what the stand-in registry answers: a chip for every pet but number 3 |
| `prometheus/prometheus.yml` | scrapes `host.docker.internal:8080/metrics` every two seconds, which on Linux needs the app started with `HOST=0.0.0.0`, and the Postgres and Kafka exporters. It also takes Tempo's remote writes and the front end's OTLP metrics |
| `grafana/provisioning/` | the Prometheus, Tempo and Loki datasources, Loki's lines linking to Tempo by `trace_id`, and the dashboard provider |
| `grafana/dashboards/petshop.json` | the dashboard itself |

`extra_hosts: host.docker.internal:host-gateway` is there for Linux, where that
name does not otherwise exist. Docker Desktop ignores it. It names the bridge,
not loopback, which is why the app needs `HOST=0.0.0.0` on Linux.

## The outbox panels

The bottom row is the relay. **Published and refused per second** is what it
carried from the outbox table to the bus, and what the bus turned away. A
refused event stays in the table and goes again on a later tick, so refusals
with no dip in published are the bus pushing back, not events lost.
`refused` reads 0 rather than nothing: the counter does not exist until the
first refusal.

**Claimed per tick** is how many rows the last claim took. A claim takes at most
100, the batch, and the panel draws a red line there: a line sitting on it
means the table is filling faster than a tick drains it. An adoption the shop
could not write to the table at all shows in the adoptions panel as
`not_recorded`.

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

## Anonymous admin

Grafana runs with the login form off and anonymous access as Admin, and Estate
lets every visitor in as an operator, who may silence alerts, with a session
secret written in `estate/estate.yaml`. That is fine for something on a laptop
for ten minutes and is not fine anywhere else.
