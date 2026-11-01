#!/usr/bin/env bash
# 票 12 基线采集：依次起三个服务（改配置前代码），记启动态平台线程数后杀掉。
set -u
ROOT="C:/Users/a1368/Desktop/better_school"
OUT="$ROOT/.scratch/virtual-thread/measure"

run_one() {
  local dir="$1" jar="$2" port="$3" name="$4"
  cd "$ROOT/backend/$dir"
  java -jar "$jar" > "$OUT/logs/app-vton-$name.log" 2>&1 &
  local bg=$!
  for i in $(seq 1 36); do
    if grep -q "Started .*Application\|APPLICATION FAILED" "$OUT/logs/app-vton-$name.log" 2>/dev/null; then break; fi
    sleep 5
  done
  if ! grep -q "Started .*Application" "$OUT/logs/app-vton-$name.log"; then
    echo "$name: FAILED TO START"; kill $bg 2>/dev/null; return 1
  fi
  local pid
  pid=$(grep -m1 -oE "INFO [0-9]+ ---" "$OUT/logs/app-vton-$name.log" | grep -oE "[0-9]+")
  sleep 5   # 等 MQ client / 池稳定
  jcmd "$pid" Thread.print > "$OUT/thread-dump-vton-$name.txt" 2>&1
  echo "$name pid=$pid platform=$(grep -c '^\"' "$OUT/thread-dump-vton-$name.txt") consume=$(grep -c 'ConsumeMessageThread_' "$OUT/thread-dump-baseline-$name.txt")"
  taskkill //PID "$pid" //F >/dev/null 2>&1
  sleep 3
}

run_one marketing-activity marketing-activity-app/target/marketing-activity-app-1.0.0-SNAPSHOT.jar 8082 activity
run_one marketing-asset marketing-asset-app/target/marketing-asset-app-1.0.0-SNAPSHOT.jar 8081 asset
run_one timeout-center timeout-center-app/target/timeout-center-app-1.0.0-SNAPSHOT.jar 8083 center
echo BASELINE3_DONE
