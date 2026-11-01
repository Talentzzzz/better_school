#!/usr/bin/env bash
# 票 04：跑「一个配置臂」的完整实测——起一个新 JVM、预热、三个并发档、采连接池与线程账。
#
#   run_arm.sh <tag> [--jfr] [额外的 java -jar 参数...]
#
# 每个臂都从**新起的 JVM** 开始（避免 JIT 冷热差异污染 A/B），先跑一轮丢弃的预热负载再测。
# 注意：PROBE/JFR 必须用 Windows PID（netstat 拿），Git Bash 的 $! 是 MSYS pid，jcmd/attach 认不到。
set -uo pipefail

TAG="$1"; shift
JFR=0
if [ "${1:-}" = "--jfr" ]; then JFR=1; shift; fi

JAVA="C:/Code/JDK21/bin/java.exe"
JCMD="C:/Code/JDK21/bin/jcmd.exe"
JAR="C:/Users/a1368/Desktop/better_school/backend/better-school/bs-app/target/bs-app-1.0.0-SNAPSHOT.jar"
ROOT="C:/Users/a1368/Desktop/better_school"
DIR="$ROOT/.scratch/virtual-thread/measure"
LOGDIR="$ROOT/backend/better-school/logs"
LOG="$LOGDIR/app-vt-$TAG.log"
LEVELS="${LEVELS:-32 128 256}"
DUR="${DUR:-30}"
WARM="${WARM:-5}"
JFR_AT="${JFR_AT:-256}"

cd "$DIR"

app_pid() { netstat -ano | grep -E ":8080\s+.*LISTENING" | head -1 | awk '{print $NF}'; }
kill_app() {
  for p in $(netstat -ano | grep -E ":8080\s+.*LISTENING" | awk '{print $NF}' | sort -u); do
    taskkill //PID "$p" //F >/dev/null 2>&1
  done
}

kill_app; sleep 2
"$JAVA" -jar "$JAR" --spring.datasource.hikari.register-mbeans=true "$@" > "$LOG" 2>&1 &
APP=""
for _ in $(seq 1 90); do
  if grep -q "Started BetterSchoolApplication" "$LOG" 2>/dev/null; then APP=$(app_pid); break; fi
  if grep -q "APPLICATION FAILED TO START\|Port 8080 already in use" "$LOG" 2>/dev/null; then break; fi
  sleep 2
done
if [ -z "$APP" ]; then
  echo "!! [$TAG] 启动失败"; tail -30 "$LOG"; kill_app; exit 1
fi
sleep 5
echo "== [$TAG] started pid=$APP  log=$LOG  extra='$*'"

# 丢弃的预热轮：让 JIT / 连接池 / 缓存进入稳态
python loadtest.py --seed seed.json --tag "$TAG-prime" --concurrency 32 --duration 25 --warmup 0 \
  --out "r-$TAG-prime.json" > /dev/null
echo "== [$TAG] primed"

for c in $LEVELS; do
  "$JAVA" HikariProbe "$APP" $((DUR + WARM + 8)) "probe-$TAG-c$c.csv" > /dev/null 2>&1 &
  PROBE=$!
  if [ "$JFR" = "1" ] && [ "$c" = "$JFR_AT" ]; then
    "$JCMD" "$APP" JFR.start name=pinning settings=vt-pinning.jfc \
      filename="$DIR/jfr-$TAG-c$c.jfr" > /dev/null
    echo "== [$TAG] JFR started"
  fi
  python loadtest.py --seed seed.json --tag "$TAG-c$c" --concurrency "$c" \
    --duration "$DUR" --warmup "$WARM" --out "r-$TAG-c$c.json" | tail -12
  if [ "$JFR" = "1" ] && [ "$c" = "$JFR_AT" ]; then
    "$JCMD" "$APP" JFR.dump name=pinning filename="$DIR/jfr-$TAG-c$c.jfr" > /dev/null
    "$JCMD" "$APP" JFR.stop name=pinning > /dev/null
    echo "== [$TAG] JFR dumped -> jfr-$TAG-c$c.jfr"
  fi
  wait "$PROBE" 2>/dev/null || true
done

kill_app
sleep 3
echo "== [$TAG] done"
