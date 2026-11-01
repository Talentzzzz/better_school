#!/usr/bin/env bash
# 票 14 消费层验收：同机 A/B 对照臂。
#   基线臂 = HEAD 编译出的 jar（消费走 client 默认 20 条 ConsumeMessageThread_* 平台线程）
#   落地臂 = 工作区 jar（消费走无界虚拟线程，前缀 mq-consume-）
# 同一份 seed、同一档负载（c=32 / 30s），在负载进行中 dump 线程：
#   Thread.print       -> 数 ConsumeMessageThread_* 平台线程（VT 不在其中）
#   Thread.dump_to_file-> 数 mq-consume-* 虚拟线程（证明"确实在消费"，防"零读数=没负载"的假阴性）
set -u
ROOT="C:/Users/a1368/Desktop/better_school"
OUT="$ROOT/.scratch/virtual-thread/measure"
V="$OUT/verify14"
mkdir -p "$V"

run_arm() {
  local label="$1" jar="$2"
  java -jar "$jar" --spring.datasource.hikari.register-mbeans=true > "$V/app-$label.log" 2>&1 &
  local bg=$!
  local started=0
  for _ in $(seq 1 40); do
    if grep -q "Started .*Application\|APPLICATION FAILED" "$V/app-$label.log" 2>/dev/null; then started=1; break; fi
    sleep 5
  done
  if [ "$started" != "1" ] || ! grep -q "Started .*Application" "$V/app-$label.log"; then
    echo "$label: FAILED TO START"; kill $bg 2>/dev/null; return 1
  fi
  local pid
  pid=$(grep -m1 -oE "INFO [0-9]+ ---" "$V/app-$label.log" | grep -oE "[0-9]+")
  echo "$label: pid=$pid"
  sleep 5

  ( cd "$OUT" && python loadtest.py --seed seed.json --tag "$label" \
      --concurrency 32 --duration 30 --out "$V/r-$label-c32.json" > "$V/load-$label.log" 2>&1 ) &
  local loadpid=$!

  # 负载进行中就采，确保消费在飞
  sleep 15
  jcmd "$pid" Thread.print > "$V/thread-dump-$label.txt" 2>&1
  jcmd "$pid" Thread.dump_to_file -format=text -overwrite "$V/vthreads-$label.txt" > "$V/vdump-$label.log" 2>&1
  wait $loadpid

  local platform vt
  platform=$(grep -c 'ConsumeMessageThread_' "$V/thread-dump-$label.txt")
  vt=$(grep -c 'mq-consume-' "$V/vthreads-$label.txt")
  echo "=== $label: ConsumeMessageThread_*平台线程=$platform  mq-consume-*虚拟线程=$vt"
  grep -oE '"ConsumeMessageThread_[^"]*"' "$V/thread-dump-$label.txt" | sort -u | head -5
  grep -oE '"mq-consume-[^"]*"' "$V/vthreads-$label.txt" | sort | head -5
  taskkill //PID "$pid" //F >/dev/null 2>&1
  sleep 4
}

run_arm baseline "$V/bs-app-baseline.jar"
run_arm vton "$ROOT/backend/better-school/bs-app/target/bs-app-1.0.0-SNAPSHOT.jar"
echo AB_CONSUME_DONE
