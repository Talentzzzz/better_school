#!/usr/bin/env bash
set -uo pipefail
JAVA="C:/Code/JDK21/bin/java.exe"
JAR="C:/Users/a1368/Desktop/better_school/backend/better-school/bs-app/target/bs-app-1.0.0-SNAPSHOT.jar"
LOGDIR="C:/Users/a1368/Desktop/better_school/backend/better-school/logs"
cd "C:/Users/a1368/Desktop/better_school/.scratch/virtual-thread/measure"
app_pid() { netstat -ano | grep -E ":8080\s+.*LISTENING" | head -1 | awk '{print $NF}'; }
kill_app() { for p in $(netstat -ano | grep -E ":8080\s+.*LISTENING" | awk '{print $NF}' | sort -u); do taskkill //PID "$p" //F >/dev/null 2>&1; done; }

kill_app; sleep 2
"$JAVA" -jar "$JAR" --spring.datasource.hikari.register-mbeans=true > "$LOGDIR/rz-vtoff.log" 2>&1 &
PID=""
for _ in $(seq 1 90); do
  if grep -q "Started BetterSchoolApplication" "$LOGDIR/rz-vtoff.log" 2>/dev/null; then PID=$(app_pid); break; fi
  if grep -q "APPLICATION FAILED TO START\|Port 8080 already in use" "$LOGDIR/rz-vtoff.log" 2>/dev/null; then break; fi
  sleep 2
done
[ -n "$PID" ] || { echo "START FAILED"; tail -20 "$LOGDIR/rz-vtoff.log"; exit 1; }
echo "pid=$PID"
sleep 5
python loadtest.py --seed seed.json --tag rz-off-upload --concurrency 256 --duration 25 --warmup 5 \
  --only-op image_upload --out r-AB-a2-upload.json
kill_app; sleep 2
echo "RERUN DONE"
