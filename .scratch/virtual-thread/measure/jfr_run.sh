#!/usr/bin/env bash
set -uo pipefail
JAVA="C:/Code/JDK21/bin/java.exe"
JCMD="C:/Code/JDK21/bin/jcmd.exe"
JAR="C:/Users/a1368/Desktop/better_school/backend/better-school/bs-app/target/bs-app-1.0.0-SNAPSHOT.jar"
LOGDIR="C:/Users/a1368/Desktop/better_school/backend/better-school/logs"
cd "C:/Users/a1368/Desktop/better_school/.scratch/virtual-thread/measure"
app_pid() { netstat -ano | grep -E ":8080\s+.*LISTENING" | head -1 | awk '{print $NF}'; }
kill_app() { for p in $(netstat -ano | grep -E ":8080\s+.*LISTENING" | awk '{print $NF}' | sort -u); do taskkill //PID "$p" //F >/dev/null 2>&1; done; }

start() {  # $1=tag $2...=extra
  local tag=$1; shift
  kill_app; sleep 2
  "$JAVA" -jar "$JAR" --spring.datasource.hikari.register-mbeans=true "$@" > "$LOGDIR/jfr-$tag.log" 2>&1 &
  for _ in $(seq 1 90); do grep -q "Started BetterSchoolApplication" "$LOGDIR/jfr-$tag.log" 2>/dev/null && return; sleep 2; done
  echo "START FAILED"; tail -5 "$LOGDIR/jfr-$tag.log"; exit 1
}

echo "### 1) 对照：行数增长后 post_list 单 op（应与早期 1756rps 对比）"
start ctrl
PID=$(app_pid); sleep 5
python loadtest.py --seed seed.json --tag ctrl-postlist-c32 --concurrency 32 --duration 20 --warmup 8 \
  --only-op post_list --out r-ctrl-postlist-c32.json | tail -2
kill_app; sleep 3

echo "### 2) 实验组开 JFR 采 pinning（VT on, 混合负载 c=256）"
start jfr --spring.threads.virtual.enabled=true
PID=$(app_pid); echo "pid=$PID"
sleep 5
# 预热（不进 JFR）
python loadtest.py --seed seed.json --tag jfr-prime --concurrency 32 --duration 20 --warmup 0 \
  --out r-jfr-prime.json > /dev/null
"$JCMD" "$PID" JFR.start name=pin settings=vt-pin-only.jfc maxsize=512m \
  filename="C:/Users/a1368/Desktop/better_school/.scratch/virtual-thread/measure/jfr-vton-c256.jfr"
"$JCMD" "$PID" JFR.check name=pin
python loadtest.py --seed seed.json --tag jfr-vton-c256 --concurrency 256 --duration 30 --warmup 5 \
  --out r-jfr-vton-c256.json | tail -3
"$JCMD" "$PID" JFR.stop name=pin filename="C:/Users/a1368/Desktop/better_school/.scratch/virtual-thread/measure/jfr-vton-c256.jfr"
kill_app; sleep 3
echo "### done"
