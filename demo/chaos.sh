#!/usr/bin/env bash
# Breaks one part of the demo at a time, so Estate has an alert to show and Ask AI something to explain.
#
#   demo/chaos.sh <scenario> [seconds]  break it; with seconds, put it back after that long
#   demo/chaos.sh restore               put everything back
#
# Scenarios, from obvious to not:
#   registry-down   the chip registry stops: adoptions fail with registry_down
#   registry-slow   the chip registry answers, but after the shop's 2s timeout: the same alert, a subtler cause
#   postgres-down   the database stops: PostgresDown, then PetshopNotReady, with nobody needing to adopt
#   kafka-down      the broker stops: KafkaDown, and the outbox fills while the bus is gone
#
# Runs from any directory, with the stack up, the shop running on the host, and the traffic service sending adoptions.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

registry_admin="http://localhost:8089/__admin"
estate="http://localhost:8095"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

look() {
  say "Now open Estate: $estate"
  echo "  Expect: $1"
  echo "  Open the alert's page and press Ask AI. A useful answer names: $2"
}

restore() {
  say "Putting everything back"
  docker compose start registry postgres kafka >/dev/null
  curl -sf -X POST "$registry_admin/settings" -H 'Content-Type: application/json' -d '{"fixedDelay": 0}' >/dev/null || true
  echo "  registry, postgres and kafka started; the registry answers without delay"
}

scenario="${1:-}"
case "$scenario" in
  registry-down)
    say "Stopping the chip registry"
    docker compose stop registry >/dev/null
    look "ChipRegistryUnreachable within a minute, as the traffic's adoptions fail" \
      "the registry is not answering (connection refused), not the shop or its database"
    ;;
  registry-slow)
    say "Slowing the chip registry to 3s a call, past the shop's 2s timeout"
    curl -sf -X POST "$registry_admin/settings" -H 'Content-Type: application/json' -d '{"fixedDelay": 3000}' >/dev/null
    look "ChipRegistryUnreachable within a minute, though the registry is up" \
      "the registry is slow rather than down: calls time out at 2s, so a longer timeout or the registry's latency"
    ;;
  postgres-down)
    say "Stopping Postgres"
    docker compose stop postgres >/dev/null
    look "PostgresDown in about 15s, then PetshopNotReady naming the database check" \
      "the database is gone; adoptions and arrivals stop, and the shop is out of rotation until it is back"
    ;;
  kafka-down)
    say "Stopping Kafka"
    docker compose stop kafka >/dev/null
    look "KafkaDown in about 30s; the outbox's rows climb on the Postgres store" \
      "the broker is gone; events wait in the outbox and nothing is lost, /stats stops moving until it is back"
    ;;
  restore)
    restore
    exit 0
    ;;
  *)
    sed -n '2,13p' "$here/chaos.sh" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac

if [[ -n "${2:-}" ]]; then
  say "Restoring in ${2}s (Ctrl-C to leave it broken)"
  sleep "$2"
  restore
else
  echo
  echo "  When done: $0 restore"
fi
