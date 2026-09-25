# Watching the numbers

Two terminals. The application runs on the host; Prometheus, Grafana and the
Postgres its outbox is in run in Docker. Compose goes first, because the
application will not start without its database.

```bash
cd demo && docker compose up -d
```

```bash
HOST=0.0.0.0 ./gradlew :app:run
```

`HOST=0.0.0.0` is for Linux. There, `host.docker.internal` is the Docker bridge
(`host-gateway`, usually 172.17.0.1) rather than the host's loopback. The app
binds 127.0.0.1 unless told otherwise, so Prometheus's scrape is refused and
every panel stays empty. Binding all interfaces lets the scrape in. It also
serves the shop to your network, which is why it is not the default. Docker
Desktop on a Mac or Windows forwards `host.docker.internal` to loopback, so
there it is harmless either way.

Then open <http://localhost:3000>. The dashboard is provisioned, so there is
nothing to import and nothing to log into.

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

## What is where

| | |
|---|---|
| `docker-compose.yml` | Prometheus, Grafana, the outbox's Postgres and a WireMock stand-in for the chip registry — the app is the thing being demonstrated |
| `registry/mappings/` | what the stand-in registry answers: a chip for every pet but number 3 |
| `prometheus/prometheus.yml` | scrapes `host.docker.internal:8080/metrics` every two seconds, which on Linux needs the app started with `HOST=0.0.0.0` |
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

## Two things the panels are making a point about

**`outcome` is a label, not three metric names.** One counter answers "how many
adoptions" and "how many were refused" because the outcome is a tag —
`sum by (outcome) (rate(petshop_adoptions_total[1m]))`.

Every branch tags the same key and only that key. Prometheus requires one set of
label names per metric name, and a series registered with a different set is
dropped **without a word** — which is how the first version of this lost every
refusal while looking like it worked.

**The durations are milliseconds.** `timed` records in milliseconds and
Prometheus convention is seconds, so `petshop_adopt_duration_sum` is not a
`_seconds_sum`. The panels say `ms` for that reason.

## Anonymous admin

Grafana runs with the login form off and anonymous access as Admin. That is fine
for something on a laptop for ten minutes and is not fine anywhere else.
