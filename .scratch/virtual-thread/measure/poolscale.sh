#!/usr/bin/env bash
set -uo pipefail
JAVA="C:/Code/JDK21/bin/java.exe"
JAR="C:/Users/a1368/Desktop/better_school/backend/better-school/bs-app/target/bs-app-1.0.0-SNAPSHOT.jar"
LOGDIR="C:/Users/a1368/Desktop/better_school/backend/better-school/logs"
cd "C:/Users/a1368/Desktop/better_school/.scratch/virtual-thread/measure"
app_pid() { netstat -ano | grep -E ":8080\s+.*LISTENING" | head -1 | awk '{print $NF}'; }
kill_app() { for p in $(netstat -ano | grep -E ":8080\s+.*LISTENING" | awk '{print $NF}' | sort -u); do taskkill //PID "$p" //F >/dev/null 2>&1; done; }
one() {
  local tag=$1 vt=$2 extra=()
  [ "$vt" = "1" ] && extra=(--spring.threads.virtual.enabled=true)
  kill_app; sleep 2
  "$JAVA" -jar "$JAR" --spring.datasource.hikari.register-mbeans=true \
    --spring.datasource.hikari.maximum-pool-size=256 "${extra[@]}" > "$LOGDIR/ps-$tag.log" 2>&1 &
  for _ in $(seq 1 90); do
    grep -q "Started BetterSchoolApplication" "$LOGDIR/ps-$tag.log" 2>/dev/null && break
    grep -q "APPLICATION FAILED TO START\|Port 8080 already in use" "$LOGDIR/ps-$tag.log" 2>/dev/null && break
    sleep 2
  done
  local PID; PID=$(app_pid)
  if [ -z "$PID" ]; then
    echo "!! [$tag] 应用没起来——本臂结果不可用，跳过"
    tail -20 "$LOGDIR/ps-$tag.log"
    kill_app; return 1
  fi
  sleep 5
  "$JAVA" HikariProbe "$PID" 52 "probe-$tag.csv" > /dev/null 2>&1 &
  local P=$!
  python loadtest.py --seed seed.json --tag "$tag" --concurrency 256 --duration 30 --warmup 5 --out "r-$tag.json" > /dev/null
  wait $P 2>/dev/null || true
  python report_one.py "$tag"
  kill_app; sleep 3
}
one PS-off-p256 0
one PS-on-p256 1
echo "POOLSCALE DONE"
