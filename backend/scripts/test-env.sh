#!/usr/bin/env bash
# 本地开发/测试常驻栈：一条命令起容器并把开发/测试两库建好，之后一整天不用再管。
#
# 集成测试连的是这里起好的栈，不是自建容器——跑测试前必须先跑一次本脚本。
# 开发库与测试库共用一个 MySQL 容器，靠库名隔离：better_school / better_school_test
# （营销域表已随 monolith-restructure 票 07 合库并入同一份 schema.sql，无独立营销库）。
#
#   scripts/test-env.sh up             起全部容器 + 等就绪 + 建好开发/测试两库
#   scripts/test-env.sh reset-test-db  先 DROP 再重建 better_school_test（改 schema / 加迁移后用）
#   scripts/test-env.sh help           显示帮助
set -euo pipefail

command -v docker >/dev/null 2>&1 || {
  echo "找不到 docker 命令：先启动 Docker Desktop" >&2
  exit 1
}

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$BASE_DIR"

SQL_DIR="$BASE_DIR/sql"
# 单体 sql 已随 better-school 工程落位（service-restructure 票 02）；营销域 SQL 随 monolith-restructure
# 票 07 合库并入单体 schema.sql（原 marketing/sql/ 四件退役，bs_marketing 库不再存在）
BS_SQL_DIR="$BASE_DIR/better-school/sql"
MYSQL_CONTAINER=bs-mysql
MYSQL_PASSWORD=bs123456
DEV_DB=better_school
TEST_DB=better_school_test

# 起完要等就绪的 7 个中间件（开发侧 + 测试侧各三个共用/独立 + 各自 nacos）。
# 不含 rocketmq-broker / rocketmq-dashboard / xxl-job-admin / es：测试不依赖它们
# （消费者被当普通 Bean 直接调用、outbox 发送失败被 catch），且 ES 还要宿主机 sysctl 才起得来。
MIDDLEWARE=(
  bs-mysql bs-redis bs-minio
  bs-nacos bs-nacos-test
  bs-rocketmq-namesrv bs-rocketmq-namesrv-test
)
READY_TIMEOUT=180

TEST_PORTS="mysql 13306 / redis 16379 / minio 19000-19001 / nacos 18848-19848 / rocketmq-namesrv 19876"

usage() {
  cat <<'EOF'
用法：scripts/test-env.sh <命令>

  up（默认）        起全部容器 + 等中间件就绪 + 建好 better_school 与 better_school_test
                    （营销域表已并入同一份 schema.sql，monolith-restructure 票 07）
  reset-test-db     先 DROP 再重建 better_school_test（改过 schema.sql 之后用；
                    只动测试库，容器要在运行中——没起就先跑 up）
  help              显示本帮助
EOF
}

# 把 stdin 上的 SQL 喂给容器里的 mysql 客户端（必须带 --default-character-set=utf8mb4，
# 否则客户端默认 latin1，中文种子数据乱码入库，敏感词匹配会失效）
sql_stdin() {
  docker exec -i "$MYSQL_CONTAINER" \
    mysql -uroot -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4
}

# 建库文件（只建库并 USE）与 schema.sql（唯一 DDL 主体）必须拼给**同一个** mysql 连接：
# USE 是连接级的，分两次 exec 会把表建到默认库里去（完整说明见 docker-compose.yml 头注释 1）
apply_init() {
  cat "$BS_SQL_DIR/$1" "$BS_SQL_DIR/schema.sql" | sql_stdin
}

wait_healthy() {
  local container="$1" status
  local deadline=$((SECONDS + READY_TIMEOUT))
  while :; do
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      "$container" 2>/dev/null || echo missing)
    case "$status" in
      healthy)
        printf '  %-28s %s\n' "$container" "$status"
        return 0
        ;;
      missing)
        printf '  %-28s 容器不存在——compose 没起来？看 docker logs %s\n' "$container" "$container" >&2
        return 1
        ;;
    esac
    if [ "$SECONDS" -ge "$deadline" ]; then
      printf '  %-28s 等 %ss 仍未就绪（当前 %s），看 docker logs %s\n' \
        "$container" "$READY_TIMEOUT" "$status" "$container" >&2
      return 1
    fi
    sleep 2
  done
}

cmd_up() {
  printf '== 1/3 起容器（docker compose up -d）\n'
  docker compose up -d

  printf '\n== 2/3 等就绪（最多 %ss）\n' "$READY_TIMEOUT"
  local container
  for container in "${MIDDLEWARE[@]}"; do
    wait_healthy "$container"
  done

  printf '\n== 3/3 建库（幂等，可重复执行）\n'
  apply_init init-dev.sql
  apply_init init-test.sql

  printf '\n就绪：%s（开发）/ %s（测试）\n' "$DEV_DB" "$TEST_DB"
  printf '测试栈 host 端口：%s\n' "$TEST_PORTS"
  printf '改过 schema.sql 或跑了新迁移后，用 scripts/test-env.sh reset-test-db 让测试库跟上。\n'
  printf '首次跑开发单体还差 xxl_job 库（非幂等，只需一次）：见 docker-compose.yml 头注释 1 的两条命令。\n'
}

cmd_reset_test_db() {
  wait_healthy "$MYSQL_CONTAINER"

  printf '== DROP DATABASE %s\n' "$TEST_DB"
  # 删除面只有测试库：库名走变量，这里不写死任何库名
  printf 'DROP DATABASE IF EXISTS `%s`;\n' "$TEST_DB" | sql_stdin

  printf '== 重建（init-test.sql + schema.sql）\n'
  apply_init init-test.sql

  printf '\n已重建：%s（开发库 %s 未触碰）\n' "$TEST_DB" "$DEV_DB"
}

case "${1:-up}" in
  up)            cmd_up ;;
  reset-test-db) cmd_reset_test_db ;;
  help | -h | --help) usage ;;
  *)
    usage >&2
    exit 2
    ;;
esac
