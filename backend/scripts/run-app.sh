#!/usr/bin/env bash
#
# 单体启动入口（virtual-thread effort 票 16，spec §5.1「JFR 常态化」；monolith-restructure
# 票 05/07 起唯一工程唯一服务，原 marketing svc 键已删）。在 backend/ 下执行：
#
#   scripts/run-app.sh <svc> [--no-jfr] [传给应用的参数...]
#
# svc ∈ bs —— 端口 8080
#
# 例：起单体并打开 Hikari JMX（spec §5.1「替代信号」手册要读池的 active / awaiting）
#   scripts/run-app.sh bs --spring.datasource.hikari.register-mbeans=true
#
# 持续 recording 不会自己落最终文件，取数靠 dump（注意：taskkill /F 硬杀会绕过
# shutdown hook、让 app-%t.jfr 停在 0 字节 —— 先 dump 再杀，或控制台 Ctrl+C）：
#   jcmd <pid> JFR.dump name=bs-vt filename=<工程目录>/logs/jfr/dump.jfr
#   jfr print --events jdk.VirtualThreadPinned --stack-depth 24 <那个 jfr>
#
# JFR 常态化的三个设定（唯一来源是本脚本 + backend/jvm/bs-vt-recording.jfc）：
#   settings   backend/jvm/bs-vt-recording.jfc —— pinning threshold=0ms + 线程统计事件
#   落盘位置   <工程目录>/logs/jfr/（随 .gitignore 的 backend/*/logs/ 一起不入库）
#   轮转       maxsize=200m / maxage=2d —— 超出后丢最老的 chunk
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JFC="$BACKEND_DIR/jvm/bs-vt-recording.jfc"
RECORDING_NAME="bs-vt"

usage() {
    cat <<'EOF'
单体启动入口（JFR 常态化）。在 backend/ 下执行：

  scripts/run-app.sh <svc> [--no-jfr] [传给应用的参数...]

  svc ∈ bs —— 端口 8080

例：起单体并打开 Hikari JMX（spec §5.1「替代信号」手册要读池的 active / awaiting）
  scripts/run-app.sh bs --spring.datasource.hikari.register-mbeans=true

取数（持续 recording 不会自己落最终文件；taskkill /F 硬杀会让 app-%t.jfr 停在 0 字节）：
  jcmd <pid> JFR.dump name=bs-vt filename=<工程目录>/logs/jfr/dump.jfr
  jfr print --events jdk.VirtualThreadPinned --stack-depth 24 <那个 jfr>

JFR 三个设定：settings=backend/jvm/bs-vt-recording.jfc、落盘=<工程目录>/logs/jfr/、
轮转=maxsize=200m + maxage=2d。
EOF
}

SVC="${1:-}"
if [ -z "$SVC" ]; then
    usage >&2
    exit 2
fi
shift

case "$SVC" in
    bs)        PROJ="better-school";  APP_DIR="bs-app";                PORT=8080 ;;
    marketing) echo "marketing svc 已随单体收口退役（票 08）：营销四域并入 bs-app，单后端 8080" >&2; exit 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知工程：$SVC（可选 bs）" >&2; exit 2 ;;
esac

USE_JFR=true
APP_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --no-jfr) USE_JFR=false ;;
        *) APP_ARGS+=("$arg") ;;
    esac
done

command -v java >/dev/null 2>&1 || { echo "找不到 java（JAVA_HOME/PATH 未配）" >&2; exit 1; }

PROJ_DIR="$BACKEND_DIR/$PROJ"
JAR="$(ls "$PROJ_DIR/$APP_DIR/target/$APP_DIR-"*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "找不到 jar：$PROJ_DIR/$APP_DIR/target/$APP_DIR-*.jar" >&2
    echo "先构建：cd $PROJ_DIR && mvn -o clean package -DskipTests" >&2
    exit 1
fi

# JVM 是原生进程，认不了 MSYS 路径（/c/Users/...）——JFR 参数里的路径必须是 Windows 形式
jvm_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}

JAVA_ARGS=()
if [ "$USE_JFR" = true ]; then
    JFR_DIR="$PROJ_DIR/logs/jfr"
    mkdir -p "$JFR_DIR"
    JAVA_ARGS=("-XX:StartFlightRecording=name=$RECORDING_NAME,settings=$(jvm_path "$JFC"),disk=true,filename=$(jvm_path "$JFR_DIR/app-%t.jfr"),maxsize=200m,maxage=2d,dumponexit=true")
fi

# 从工程目录起：xxl 执行器与相对路径日志都按 cwd 落
cd "$PROJ_DIR"

echo "[run-app] svc=$SVC 端口=$PORT 目录=$(pwd)"
echo "[run-app] jar=$JAR"
if [ "$USE_JFR" = true ]; then
    echo "[run-app] JFR 已挂：${JAVA_ARGS[0]}"
    echo "[run-app] 取数：jcmd <pid> JFR.dump name=$RECORDING_NAME filename=$PROJ_DIR/logs/jfr/dump.jfr"
else
    echo "[run-app] --no-jfr：本次不挂 recording"
fi

exec java ${JAVA_ARGS[@]+"${JAVA_ARGS[@]}"} -jar "$JAR" ${APP_ARGS[@]+"${APP_ARGS[@]}"}
