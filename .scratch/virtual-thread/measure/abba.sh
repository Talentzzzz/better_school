#!/usr/bin/env bash
set -uo pipefail
JAVA="C:/Code/JDK21/bin/java.exe"
JAR="C:/Users/a1368/Desktop/better_school/backend/better-school/bs-app/target/bs-app-1.0.0-SNAPSHOT.jar"
LOGDIR="C:/Users/a1368/Desktop/better_school/backend/better-school/logs"
cd "C:/Users/a1368/Desktop/better_school/.scratch/virtual-thread/measure"
app_pid() { netstat -ano | grep -E ":8080\s+.*LISTENING" | head -1 | awk '{print $NF}'; }
kill_app() { for p in $(netstat -ano | grep -E ":8080\s+.*LISTENING" | awk '{print $NF}' | sort -u); do taskkill //PID "$p" //F >/dev/null 2>&1; done; }

one() {   # $1=tag $2=vt(0/1)
  local tag=$1 vt=$2 extra=()
  [ "$vt" = "1" ] && extra=(--spring.threads.virtual.enabled=true)
  kill_app; sleep 2
  "$JAVA" -jar "$JAR" --spring.datasource.hikari.register-mbeans=true "${extra[@]}" > "$LOGDIR/ab-$tag.log" 2>&1 &
  for _ in $(seq 1 90); do
    grep -q "Started BetterSchoolApplication" "$LOGDIR/ab-$tag.log" 2>/dev/null && break
    grep -q "APPLICATION FAILED TO START\|Port 8080 already in use" "$LOGDIR/ab-$tag.log" 2>/dev/null && break
    sleep 2
  done
  local PID; PID=$(app_pid)
  if [ -z "$PID" ]; then
    echo "!! [$tag] 应用没起来——本臂结果不可用，跳过（否则负载照样会跑完并落一份脏 JSON）"
    tail -20 "$LOGDIR/ab-$tag.log"
    kill_app; return 1
  fi
  sleep 5
  "$JAVA" HikariProbe "$PID" 78 "probe-$tag.csv" > /dev/null 2>&1 &
  local P=$!
  python loadtest.py --seed seed.json --tag "$tag-mixed" --concurrency 256 --duration 30 --warmup 5 --out "r-$tag-mixed.json" > /dev/null
  python loadtest.py --seed seed.json --tag "$tag-upload" --concurrency 256 --duration 25 --warmup 5 --only-op image_upload --out "r-$tag-upload.json" > /dev/null
  wait $P 2>/dev/null || true
  python report_one.py "$tag"
  kill_app; sleep 3
}

one AB-a 0
one AB-b 1
one AB-c 0
one AB-d 1
echo "ABBA DONE"
