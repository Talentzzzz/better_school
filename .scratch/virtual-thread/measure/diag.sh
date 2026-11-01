#!/usr/bin/env bash
set -uo pipefail
JAVA="C:/Code/JDK21/bin/java.exe"
JAR="C:/Users/a1368/Desktop/better_school/backend/better-school/bs-app/target/bs-app-1.0.0-SNAPSHOT.jar"
LOGDIR="C:/Users/a1368/Desktop/better_school/backend/better-school/logs"
cd "C:/Users/a1368/Desktop/better_school/.scratch/virtual-thread/measure"

app_pid() { netstat -ano | grep -E ":8080\s+.*LISTENING" | head -1 | awk '{print $NF}'; }
kill_app() { for p in $(netstat -ano | grep -E ":8080\s+.*LISTENING" | awk '{print $NF}' | sort -u); do taskkill //PID "$p" //F >/dev/null 2>&1; done; }

run() {          # $1=tag  $2...=java 额外参数
  local tag=$1; shift
  kill_app; sleep 2
  "$JAVA" -jar "$JAR" --spring.datasource.hikari.register-mbeans=true "$@" > "$LOGDIR/diag-$tag.log" 2>&1 &
  local pid=""
  for _ in $(seq 1 90); do
    grep -q "Started BetterSchoolApplication" "$LOGDIR/diag-$tag.log" 2>/dev/null && { pid=$(app_pid); break; }
    sleep 2
  done
  if [ -z "$pid" ]; then echo "=== [$tag] START FAILED ==="; tail -5 "$LOGDIR/diag-$tag.log"; return; fi
  sleep 5
  echo "=== [$tag] pid=$pid ==="
  python loadtest.py --seed seed.json --tag "$tag" --concurrency 32 --duration 20 --warmup 8 \
    --only-op post_list --out "diag-$tag.json" | tail -3
  kill_app; sleep 4
}

run debug
run warn --logging.level.com.betterschool=warn
echo "=== diag done ==="
