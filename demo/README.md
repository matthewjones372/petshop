# Watching the numbers

Two terminals. The application runs on the host. Everything else runs in
Docker: Prometheus, Grafana, Alertmanager, Estate, the Postgres the outbox is in,
Kafka and its schema registry, an exporter for each of those two, and a stand-in
chip registry. Compose goes first,
because the application will not start without its database.

```bash
cd demo && docker compose up -d
```

```bash
BUS=kafka HOST=0.0.0.0 ./gradlew :app:run
```

`BUS=kafka` puts the shop's events on the demo's Kafka, which is what gives Estate's
Kafka store and the projection's lag something to show. Without it the events stay
in the process.

`HOST=0.0.0.0` is for Linux. There, `host.docker.internal` is the Docker bridge
(`host-gateway`, usually 172.17.0.1) rather than the host's loopback. The app
binds 127.0.0.1 unless told otherwise, so Prometheus's scrape is refused and
every panel stays empty. Binding all interfaces lets the scrape in. It also
serves the shop to your network, which is why it is not the default. Docker
Desktop on a Mac or Windows forwards `host.docker.internal` to loopback, so
there it is harmless either way.

Then open Grafana at <http://localhost:3000>, or Estate at
<http://localhost:8095> (see [Estate](#estate) below). Grafana's dashboard is
provisioned, so there is nothing to import and nothing to log into.

Make something happen:

```bash
curl -X POST localhost:8080/pets/1/adoption   # taken
curl -X POST localhost:8080/pets/1/adoption   # already_adopted
curl -X POST localhost:8080/pets/999/adoption # no_such_pet
curl -X POST localhost:8080/pets/3/adoption   # not_chipped: the registry has no chip for Mrs Peel
docker compose stop registry
curl -X POST localhost:8080/pets/2/adoption   # registry_down, and Barnaby stays in the shop
docker compose start registry
```

Arrivals happen on their own, every `petshop.arrivalsEvery`.

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
curl -X POST localhost:8080/pets/2/adoption   # registry_down
docker compose start registry
```

Stop the database and two alerts fire within about ten seconds, with nobody adopting anything:
`PostgresDown`, from the exporter's `pg_up`, on the Postgres store and the map's edge to it, and `PetshopNotReady`,
from the shop's own health check, whose `database` probe stops answering. Stop Kafka and `KafkaDown` fires on the
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
| `estate/catalog.yaml` | the petshop as one service in one environment, with its Postgres and Kafka as stores: its load from adoptions, its JVM and pool, the stores' stats, the vitals and the map |
| `estate/estate.yaml` | Estate's settings: no sign-in (every visitor is an operator), and the demo's Prometheus and Alertmanager as its sources |
| `prometheus/rules.yml` | the alerts: the shop's keep `job="petshop"`, which is how Estate knows an alert is the petshop's, and the stores' carry `store:` naming theirs |
| `alertmanager/alertmanager.yml` | routes every alert nowhere: Estate reads them, and keeps its silences there |

## What is where

| | |
|---|---|
| `docker-compose.yml` | Prometheus, Alertmanager, Grafana, Estate, the outbox's Postgres, Kafka and its schema registry, and a WireMock stand-in for the chip registry. The app itself runs on the host |
| `registry/mappings/` | what the stand-in registry answers: a chip for every pet but number 3 |
| `prometheus/prometheus.yml` | scrapes `host.docker.internal:8080/metrics` every two seconds, which on Linux needs the app started with `HOST=0.0.0.0`, and the Postgres and Kafka exporters |
| `grafana/provisioning/` | the datasource and the dashboard provider |
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

**The durations are milliseconds.** `timed` records in milliseconds and
Prometheus convention is seconds, so `petshop_adopt_duration_sum` is not a
`_seconds_sum`. The panels say `ms` for that reason.

## Anonymous admin

Grafana runs with the login form off and anonymous access as Admin, and Estate
lets every visitor in as an operator, who may silence alerts, with a session
secret written in `estate/estate.yaml`. That is fine for something on a laptop
for ten minutes and is not fine anywhere else.
