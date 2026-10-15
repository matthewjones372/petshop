#!/usr/bin/env bash
# Ramps a shop of its own to PEAK requests a second, and samples what the shop, the generator and Postgres spent.
# Usage: loadtest/heavy.sh <out-dir>, after ./gradlew :app:installDist :loadtest:installDemoTraffic.
# PEAK, RAMP, HOLD, PETS and TIMEOUT go to HeavyTraffic; SHOP_OPTS and GENERATOR_OPTS are each JVM's flags.
# Postgres on 55499, the registry stub on 8189 and the shop on 8180, so the demo's 55432, 8089 and 8080 stay free.
set -euo pipefail

out=$(mkdir -p "$1" && cd "$1" && pwd)
root=$(cd "$(dirname "$0")/.." && pwd)
pets=${PETS:-100}
pg=petshop-heavy-pg
registry=petshop-heavy-registry
shop_url=http://127.0.0.1:8180

shop_pid=
sampler_pid=
cleanup() {
  [ -n "$sampler_pid" ] && kill "$sampler_pid" 2>/dev/null || true
  [ -n "$shop_pid" ] && kill "$shop_pid" 2>/dev/null && wait "$shop_pid" 2>/dev/null || true
  docker rm -f "$pg" "$registry" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Another process busy on this machine is measured along with the shop, so wait for a quiet one first.
quiet=${QUIET:-$(sysctl -n hw.ncpu 2>/dev/null || nproc)}
for _ in $(seq 1 "${QUIET_WAIT:-120}"); do
  load=$(uptime | awk -F'load averages?: ' '{ print $2 }' | tr -d ',' | awk '{ print $1 }')
  awk -v l="$load" -v q="$quiet" 'BEGIN { exit !(l < q) }' && break
  sleep 5
done
awk -v l="$load" -v q="$quiet" 'BEGIN { exit !(l < q) }' || { echo "Load average $load never fell under $quiet" >&2; exit 1; }
uptime >"$out/load-before.txt"

docker rm -f "$pg" "$registry" >/dev/null 2>&1 || true
# 200 connections, so a SHOP_OPTS that widens the pool does not meet Postgres's default of 100.
docker run -d --name "$pg" -e POSTGRES_USER=petshop -e POSTGRES_PASSWORD=petshop -e POSTGRES_DB=petshop \
  -p 55499:5432 postgres:17-alpine -c max_connections=200 >/dev/null
docker run -d --name "$registry" -v "$root/demo/registry/mappings:/home/wiremock/mappings:ro" -p 8189:8080 \
  wiremock/wiremock:3.13.1 --no-request-journal --async-response-enabled=true >/dev/null
until docker exec "$pg" pg_isready -U petshop -d petshop >/dev/null 2>&1; do sleep 0.2; done
sleep 1
# The changelog adopts a pets table that already exists, so the shop opens with this shelf.
docker exec "$pg" psql -qU petshop -d petshop -c "CREATE TABLE pets (id BIGINT PRIMARY KEY, name TEXT NOT NULL,
  species TEXT NOT NULL, adopted BOOLEAN NOT NULL);
  INSERT INTO pets SELECT n, 'Pet ' || n, (ARRAY['Cat', 'Dog', 'Parrot', 'Tortoise'])[n % 4 + 1], false
  FROM generate_series(1, $pets) AS n;" >/dev/null
until curl -sf -o /dev/null http://127.0.0.1:8189/__admin/health; do sleep 0.2; done

# No arrivals, so the catalogue is the shelf above for the whole run.
PORT=8180 DATABASE_URL=jdbc:postgresql://127.0.0.1:55499/petshop REGISTRY_URL=http://127.0.0.1:8189 \
  JAVA_OPTS="-Dpetshop.arrivalsEvery=1h ${SHOP_OPTS:-}" "$root/app/build/install/app/bin/app" >"$out/shop.log" 2>&1 &
shop_pid=$!
until curl -sf -o /dev/null "$shop_url/pets"; do sleep 0.1; done

# A row every second or two: each JVM's CPU over the interval (100 is one core, from CPU time rather than ps's decaying
# %cpu), the shop's threads and pool, Postgres's CPU, and the sockets on the shop's port.
metric() { awk -v m="$1" '$1 == m || index($1, m "{") == 1 { sum += $NF } END { printf "%d", sum }'; }
cpu_seconds() { ps -o time= -p "$1" 2>/dev/null | awk -F: '{ s = 0; for (i = 1; i <= NF; i++) s = s * 60 + $i; print s }'; }
sample() {
  echo -e "time\tload\tshop_cpu\tgenerator_cpu\tshop_threads\tpool_active\tpool_pending\tpostgres_cpu\tsockets"
  local then shop_then generator_then
  then=$(python3 -c 'import time; print(time.time())'); shop_then=$(cpu_seconds "$shop_pid"); generator_then=0
  while true; do
    sleep 1
    generator=$(pgrep -f 'java.*petshop[.]load[.]HeavyTrafficKt' | head -1 || true)
    scraped=$(curl -s --max-time 1 "$shop_url/metrics" || true)
    postgres=$(docker stats --no-stream --format '{{.CPUPerc}}' "$pg" | tr -d '%')
    sockets=$(netstat -an -p tcp | grep -c '127[.]0[.]0[.]1[.]8180 ' || true)
    now=$(python3 -c 'import time; print(time.time())')
    shop_now=$(cpu_seconds "$shop_pid"); generator_now=$([ -n "$generator" ] && cpu_seconds "$generator" || echo 0)
    percent() { awk -v a="$1" -v b="$2" -v t0="$then" -v t1="$now" 'BEGIN { printf "%.0f", (b - a) * 100 / (t1 - t0) }'; }
    row=("$(date +%s)" "$(uptime | awk -F'load averages?: ' '{ print $2 }' | awk '{ print $1 }')"
      "$(percent "$shop_then" "$shop_now")" "$(percent "$generator_then" "$generator_now")"
      "$(echo "$scraped" | metric jvm_threads_live_threads)" "$(echo "$scraped" | metric hikaricp_connections_active)"
      "$(echo "$scraped" | metric hikaricp_connections_pending)" "$postgres" "$sockets")
    (IFS=$'\t'; echo "${row[*]}")
    then=$now; shop_then=$shop_now; generator_then=$generator_now
  done
}
sample >"$out/samples.tsv" &
sampler_pid=$!

PETSHOP_URL=$shop_url REPORTS=$out java -Xmx4g -XX:+ExitOnOutOfMemoryError ${GENERATOR_OPTS:-} \
  -cp "$root/loadtest/build/demo-traffic/lib/*" petshop.load.HeavyTrafficKt | tee "$out/generator.txt"

uptime >"$out/load-after.txt"
docker exec "$pg" psql -qtAU petshop -d petshop -c "SELECT count(*) FROM pets WHERE adopted" >"$out/adopted-in-table.txt"
