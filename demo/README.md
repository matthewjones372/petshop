# Watching the numbers

Two terminals. The application runs on the host; Prometheus and Grafana run in
Docker and scrape it.

```bash
./gradlew :app:run
```

```bash
cd demo && docker compose up -d
```

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
| `docker-compose.yml` | Prometheus, Grafana and a WireMock stand-in for the chip registry — the app is the thing being demonstrated |
| `registry/mappings/` | what the stand-in registry answers: a chip for every pet but number 3 |
| `prometheus/prometheus.yml` | scrapes `host.docker.internal:8080/metrics` every two seconds |
| `grafana/provisioning/` | the datasource and the dashboard provider |
| `grafana/dashboards/petshop.json` | the dashboard itself |

`extra_hosts: host.docker.internal:host-gateway` is there for Linux, where that
name does not otherwise exist. Docker Desktop ignores it.

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
