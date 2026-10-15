#!/usr/bin/env bash
# What the shop costs as its own process: start-up, heap, RSS, CPU and the classes it loads.
# Usage: loadtest/footprint.sh <out-dir>, after ./gradlew :app:installDist.
# JAVA_HOME picks the JDK. A throwaway Postgres runs on 55432, the port application.conf defaults to.
set -euo pipefail

out=$1
root=$(cd "$(dirname "$0")/.." && pwd)
app=$root/app/build/install/app/bin/app
url=http://127.0.0.1:8080/pets
pg=petshop-footprint-pg
mkdir -p "$out"

now() { python3 -c 'import time; print(time.time())'; }
cpu() { ps -o time= -p "$1" | awk -F: '{ print ($1 * 60) + $2 }'; }

if ! docker inspect "$pg" >/dev/null 2>&1; then
  docker run -d --name "$pg" -e POSTGRES_USER=petshop -e POSTGRES_PASSWORD=petshop -e POSTGRES_DB=petshop \
    -p 55432:5432 postgres:17-alpine >/dev/null
  until docker exec "$pg" pg_isready -U petshop -d petshop >/dev/null 2>&1; do sleep 0.2; done
  sleep 1
fi
fresh() { docker exec -e PGOPTIONS='-c client_min_messages=warning' "$pg" psql -qU petshop -d petshop -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;' >/dev/null; }

# No arrivals, so every launch serves the same three pets.
base_opts="-Dpetshop.arrivalsEvery=1h"

# Start-up, from launch to the first answer to GET /pets, three times on an empty database.
for run in 1 2 3; do
  fresh
  started=$(now)
  JAVA_OPTS="$base_opts" "$app" >"$out/startup-$run.log" 2>&1 &
  pid=$!
  until curl -sf -o /dev/null "$url"; do sleep 0.01; done
  answered=$(now)
  python3 -c "print(round(($answered - $started) * 1000))" >"$out/startup-$run.ms"
  kill "$pid"; wait "$pid" 2>/dev/null || true
done

# One more launch, logging every class it loads and recording heap with JFR, then three hundred thousand browses.
fresh
JAVA_OPTS="$base_opts -Xlog:class+load=info:file=$out/classes.log -XX:StartFlightRecording=filename=$out/run.jfr,settings=default" \
  "$app" >"$out/run.log" 2>&1 &
pid=$!
until curl -sf -o /dev/null "$url"; do sleep 0.01; done
cpu "$pid" >"$out/cpu-at-start.s"
ab -q -k -n 300000 -c 8 "$url" >"$out/ab.txt" 2>&1
cpu "$pid" >"$out/cpu-at-end.s"
ps -o rss= -p "$pid" >"$out/rss-at-end.kb"
"${JAVA_HOME:+$JAVA_HOME/bin/}jcmd" "$pid" GC.heap_info >"$out/heap-info.txt"
"${JAVA_HOME:+$JAVA_HOME/bin/}jcmd" "$pid" JFR.dump filename="$out/run.jfr" >/dev/null
kill "$pid"; wait "$pid" 2>/dev/null || true

"${JAVA_HOME:+$JAVA_HOME/bin/}jfr" print --events jdk.GCHeapSummary "$out/run.jfr" |
  awk '/heapUsed/ { gsub(/[^0-9.]/, "", $3); unit = $4; v = $3 + 0; if (unit ~ /^MB/) v *= 1; else if (unit ~ /^kB/) v /= 1024; else if (unit ~ /^GB/) v *= 1024; if (v > max) max = v } END { printf "%.1f\n", max }' \
  >"$out/peak-heap.mb"

classes=$out/classes.log
{
  echo "classes	$(grep -c 'class,load' "$classes")"
  for prefix in org.apache.pekko scala. org.eclipse.jetty com.fasterxml.jackson com.fasterxml.jackson.databind kotlinx.serialization; do
    echo "$prefix	$(grep 'class,load' "$classes" | awk '{ print $2 }' | grep -c "^$prefix" || true)"
  done
  echo "jars	$(grep -o 'source: [^ ]*\.jar' "$classes" | sort -u | wc -l | tr -d ' ')"
} >"$out/classes.tsv"
grep -o 'source: [^ ]*\.jar' "$classes" | sed 's|.*/||' | sort -u >"$out/jars-loaded.txt"
