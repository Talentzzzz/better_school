#!/usr/bin/env python3
"""超时中心内嵌化切换的数据搬迁工具（一次性，票 07）。

把 `bs_timeout.timeout_task_*` 里的存量在途/死信行搬进
`better_school.timeout_task_*`，并支持回滚方向的逆向搬迁。

为什么需要它（而不是直接 `INSERT ... SELECT`）：

* **槽位公式变了**。旧中心 `slot = floorMod(hash(len(taskType)+":"+taskType+bizId), 16)`，
  新单体 `slot = floorMod(hash(bizId), 16)`。目标分表号必须按新公式重算，否则新扫描器
  永远看不到这一行（写错表 = 静默丢失，不是报错）。
* **目标表里可能已有新代码写入的行**。新代码先滚动上线再搬迁时，同键以目标行为准
  （目标行是新一代注册的事实源），搬迁只补缺、不覆盖。
* **`timeout_register_outbox` 里可能还有 PENDING 残留**。新代码不再有 outbox 发送器；
  这些行如果只留在旧表里，部署完成后就永远发不出去，必须一并搬。

安全边界（脚本会做/不会做的事）：

* 前置检查旧扫描 job `timeoutTaskScanJob` 必须已停（`trigger_status = 0`），
  新扫描 job `timeoutTaskScan` 也必须已停（前向搬迁/校验期间不能让新代码把刚搬进去的行结算掉），
  除非显式 `--force`。这一条是「搬迁期间到点」竞态的主要防线。
* 前向搬迁只搬 `WAITING` / `RETRYING` / `FAILED`：前两者是未完成动作，`FAILED` 是死信，
  搬过去才能在单体的死信管理面里继续重发/取消。`SUCCESS` / `CANCELED` 是历史，
  随 `bs_timeout` 留档，不搬。
* 不修改源库。搬迁可重复执行：同键已存在就跳过并记入报告。
* 整个写入在一个事务里；任一条失败回滚全部，重跑安全。

用法见 `backend/docs/ops/timeout-inline-cutover.md`。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Iterable, Sequence

SCRIPT_VERSION = "1.0.0"
SHARD_COUNT = 16
TABLE_PREFIX = "timeout_task"
ACTIVE_STATUSES = ("WAITING", "RETRYING")
MIGRATED_STATUSES = ("WAITING", "RETRYING", "FAILED")
ALL_STATUSES = ("WAITING", "RETRYING", "SUCCESS", "FAILED", "CANCELED")
OLD_SCAN_HANDLER = "timeoutTaskScanJob"
NEW_SCAN_HANDLER = "timeoutTaskScan"
OUTBOX_TABLE = "timeout_register_outbox"
SOURCE_KEY_CHUNK = 200  # 旧中心按键查状态时单条 SQL 的键数上限（16 表 UNION）

EXIT_OK = 0
EXIT_PREFLIGHT = 2
EXIT_VERIFY = 3


class MigrationError(RuntimeError):
    """可预期的操作失败：打印后以非零码退出，不抛栈。"""


# ---------------------------------------------------------------------------
# 槽位公式（必须与 Java 实现逐字节一致）
# ---------------------------------------------------------------------------

def java_hash(text: str) -> int:
    """`java.lang.String.hashCode()`：按 UTF-16 code unit 计算，返回有符号 32 位。"""
    units = text.encode("utf-16-be")
    value = 0
    for index in range(0, len(units), 2):
        unit = (units[index] << 8) | units[index + 1]
        value = (31 * value + unit) & 0xFFFFFFFF
    return value - 0x100000000 if value >= 0x80000000 else value


def java_length(text: str) -> int:
    """`java.lang.String.length()`：UTF-16 code unit 数（不是 Unicode code point 数）。"""
    return len(text.encode("utf-16-be")) // 2


def old_slot(task_type: str, biz_id: str) -> int:
    """旧中心槽位：`floorMod(hash(len(taskType) + ":" + taskType + bizId), 16)`。"""
    key = f"{java_length(task_type)}:{task_type}{biz_id}"
    return java_hash(key) % SHARD_COUNT


def new_slot(biz_id: str) -> int:
    """新单体槽位：`floorMod(hash(bizId), 16)`（`TimeoutTaskShardSlots.slotOf`）。"""
    return java_hash(biz_id) % SHARD_COUNT


def physical_table(slot: int) -> str:
    if not 0 <= slot < SHARD_COUNT:
        raise MigrationError(f"槽位越界：{slot}")
    return f"{TABLE_PREFIX}_{slot:02d}"


# ---------------------------------------------------------------------------
# SQL 拼装：字符串一律走 UTF-8 hex，避免引号/反斜杠/换行注入
# ---------------------------------------------------------------------------

def qi(identifier: str) -> str:
    """MySQL 标识符引用。"""
    return "`" + identifier.replace("`", "``") + "`"


def qname(schema: str, table: str) -> str:
    return f"{qi(schema)}.{qi(table)}"


def sql_text(value: str) -> str:
    """安全的 utf8mb4 字符串字面量（用于 WHERE / information_schema / 时间列等）。"""
    return "CONVERT(X'" + value.encode("utf-8").hex() + "' USING utf8mb4)"


def sql_datetime(value: str) -> str:
    # DATE_FORMAT 产出的固定形态，直接引用无注入面；保留引号是为了让 SQL 可读。
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def require_datetime(value: str, context: str) -> str:
    """拒绝 NULL / 零值日期：写成 `'NULL'` 会在目标端静默变成 0000-00-00 或被拒。"""
    if value in ("", "NULL", "0000-00-00 00:00:00"):
        raise MigrationError(f"{context} 的日期列为空/零值：{value!r}")
    return value


# ---------------------------------------------------------------------------
# MySQL CLI 封装（仓库/生产都已有 mysql 客户端，不额外装 Python 驱动）
# ---------------------------------------------------------------------------

@dataclass
class SqlClient:
    mysql_bin: str
    host: str
    port: int
    user: str
    password: str | None

    def _command(self) -> list[str]:
        command = [
            self.mysql_bin,
            "--default-character-set=utf8mb4",
            "--batch",
            "--skip-column-names",
            "-h", self.host,
            "-P", str(self.port),
            "-u", self.user,
        ]
        return command

    def run(self, sql: str) -> str:
        env = os.environ.copy()
        if self.password is not None:
            env["MYSQL_PWD"] = self.password
        try:
            completed = subprocess.run(
                self._command(),
                input=sql,
                text=True,
                capture_output=True,
                encoding="utf-8",
                errors="replace",
                env=env,
                check=False,
            )
        except FileNotFoundError as exc:
            raise MigrationError(
                f"找不到 mysql 客户端：{self.mysql_bin!r}；用 --mysql 指定路径"
            ) from exc
        if completed.returncode != 0:
            detail = (completed.stderr or completed.stdout or "").strip()
            raise MigrationError(f"mysql 执行失败（exit {completed.returncode}）：{detail}")
        return completed.stdout

    def query(self, sql: str) -> list[list[str]]:
        output = self.run(sql)
        rows: list[list[str]] = []
        for line in output.splitlines():
            if not line.strip():
                continue
            rows.append(line.rstrip("\r").split("\t"))
        return rows


# ---------------------------------------------------------------------------
# 数据模型
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class TaskRow:
    table: str
    task_type: str
    biz_id: str
    action_time: str
    next_fire_time: str
    status: str
    retry_count: int
    created_at: str
    updated_at: str
    origin: str = "center"

    @property
    def key(self) -> tuple[str, str]:
        return self.task_type, self.biz_id


@dataclass
class Config:
    mysql_bin: str = "mysql"
    host: str = "127.0.0.1"
    port: int = 3306
    user: str = "root"
    password: str | None = None
    source_schema: str = "bs_timeout"
    target_schema: str = "better_school"
    xxl_schema: str = "xxl_job"
    callback_group: str = "better-school"
    dry_run: bool = False
    force: bool = False
    allow_existing_target: bool = False
    cancel_stale_old: bool = True
    report_path: str | None = None


@dataclass
class PlannedInsert:
    target_table: str
    row: TaskRow


@dataclass
class PlannedSkip:
    row: TaskRow
    target_table: str | None
    reason: str


@dataclass
class ForwardPlan:
    inserts: list[PlannedInsert] = field(default_factory=list)
    skips: list[PlannedSkip] = field(default_factory=list)
    source_rows: list[TaskRow] = field(default_factory=list)
    outbox_rows: list[TaskRow] = field(default_factory=list)


@dataclass
class ReversePlan:
    inserts: list[PlannedInsert] = field(default_factory=list)
    revives: list[PlannedInsert] = field(default_factory=list)
    cancels: list[PlannedInsert] = field(default_factory=list)
    skips: list[PlannedSkip] = field(default_factory=list)
    target_rows: list[TaskRow] = field(default_factory=list)


# ---------------------------------------------------------------------------
# 查询
# ---------------------------------------------------------------------------

def timeout_table_names() -> list[str]:
    return [physical_table(slot) for slot in range(SHARD_COUNT)]


def existing_tables(client: SqlClient, schema: str) -> set[str]:
    names = timeout_table_names()
    in_list = ", ".join(sql_text(name) for name in names)
    rows = client.query(
        "SELECT table_name FROM information_schema.tables "
        f"WHERE table_schema = {sql_text(schema)} AND table_name IN ({in_list})"
    )
    return {row[0] for row in rows if row}


def table_count(client: SqlClient, schema: str, table: str) -> int:
    rows = client.query(f"SELECT COUNT(*) FROM {qname(schema, table)}")
    return int(rows[0][0]) if rows else 0


def ensure_timeout_tables(
    client: SqlClient, schema: str, label: str, hint: str = ""
) -> None:
    existing = existing_tables(client, schema)
    missing = [name for name in timeout_table_names() if name not in existing]
    if missing:
        raise MigrationError(f"{label} {schema} 缺少分表：{', '.join(missing)}{hint}")


def job_statuses(client: SqlClient, schema: str) -> dict[str, int] | None:
    """读扫描 job 的 trigger_status；整个 xxl 库/表读不到时返回 None（调用方决定是否放行）。"""
    try:
        rows = client.query(
            "SELECT executor_handler, trigger_status FROM "
            f"{qname(schema, 'xxl_job_info')} WHERE executor_handler IN "
            f"({sql_text(OLD_SCAN_HANDLER)}, {sql_text(NEW_SCAN_HANDLER)})"
        )
    except MigrationError:
        return None
    return {row[0]: int(row[1]) for row in rows if len(row) >= 2}


def check_job_statuses(
    cfg: Config, statuses: dict[str, int] | None, mode: str
) -> list[str]:
    """两个扫描 job 的前置守卫；读不到 xxl 库时默认拒绝，`--force` 才放行。"""
    warnings: list[str] = []
    if statuses is None:
        if not cfg.force:
            raise MigrationError(
                f"读不到 {cfg.xxl_schema}.xxl_job_info，无法确认扫描 job 已停。"
                "先核对 --xxl-schema 与连接权限；确认要跳过这项检查再加 --force。"
            )
        warnings.append(
            f"⚠️ 读不到 {cfg.xxl_schema}.xxl_job_info，已按 --force 越过；无法确认 job 已停"
        )
        return warnings

    if not statuses:
        warnings.append(
            f"xxl-job 中没有 {OLD_SCAN_HANDLER}/{NEW_SCAN_HANDLER} 行；确认 seed/清理状态"
        )
        return warnings

    old_status = statuses.get(OLD_SCAN_HANDLER)
    if old_status is None:
        warnings.append(f"xxl-job 中没有 {OLD_SCAN_HANDLER} 行（可能已清理）")
    elif old_status == 1 and not cfg.force:
        if mode == "reverse":
            raise MigrationError(
                f"逆向前旧扫描 job {OLD_SCAN_HANDLER} 仍为启用状态（trigger_status=1）。"
                "回滚期间它会把刚写回旧库的行结算掉；先停旧 job 并等过一个 10s 周期，"
                "确需并发可加 --force。"
            )
        raise MigrationError(
            f"旧扫描 job {OLD_SCAN_HANDLER} 仍为启用状态（trigger_status=1）。"
            "先把它的 trigger_status 置 0 并等过至少一个 10s 周期，再用 --force 才可越过。"
        )
    elif old_status == 1:
        warnings.append(f"⚠️ {OLD_SCAN_HANDLER} 仍启用，已按 --force 越过（有并发到点风险）")

    new_status = statuses.get(NEW_SCAN_HANDLER)
    if new_status is None:
        warnings.append(
            f"xxl-job 中没有 {NEW_SCAN_HANDLER} 行：Phase 3 前需执行 "
            "better-school/sql/xxl-job-tasks.sql（幂等）"
        )
    elif new_status == 1 and not cfg.force:
        if mode == "reverse":
            raise MigrationError(
                f"新扫描 job {NEW_SCAN_HANDLER} 仍启用（trigger_status=1）。回滚前先禁用它并"
                "停掉新版本实例，否则搬迁读到的行会被并发结算。确已停止可加 --force。"
            )
        raise MigrationError(
            f"新扫描 job {NEW_SCAN_HANDLER} 仍为启用状态（trigger_status=1）。"
            "前向搬迁 + 校验期间必须关掉它：否则新代码会在校验前把刚搬进去的行结算掉，"
            "验证读到的状态会漂移（不是搬迁失败）。停掉并等过一个 10s 周期后再跑；"
            "确需并发扫描可加 --force。"
        )
    elif new_status == 1:
        warnings.append(
            f"⚠️ {NEW_SCAN_HANDLER} 仍启用，已按 --force 越过；"
            "校验可能因新扫描器就地结算而误报状态漂移"
        )

    return warnings


def _select_rows_sql(schema: str, tables: Sequence[str], statuses: Sequence[str]) -> str:
    status_list = ", ".join(sql_text(status) for status in statuses)
    selects = []
    for table in tables:
        selects.append(
            f"SELECT {sql_text(table)}, HEX(task_type), HEX(biz_id), "
            f"DATE_FORMAT(action_time, '%Y-%m-%d %H:%i:%s'), "
            f"DATE_FORMAT(next_fire_time, '%Y-%m-%d %H:%i:%s'), "
            f"status, retry_count, "
            f"DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s'), "
            f"DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') "
            f"FROM {qname(schema, table)} WHERE status IN ({status_list})"
        )
    return "\nUNION ALL\n".join(selects)


def task_row_from_values(values: Sequence[str], origin: str) -> TaskRow:
    """解析 `task_type/biz_id/...` 九列查询结果（分表与按键查询共用）。"""
    if len(values) != 9:
        raise MigrationError(f"查询结果列数不符（期望 9，实际 {len(values)}）：{values!r}")
    try:
        task_type = bytes.fromhex(values[1]).decode("utf-8")
        biz_id = bytes.fromhex(values[2]).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as exc:
        raise MigrationError(f"无法解码 {values[0]} 的行：{values!r}") from exc
    try:
        retry_count = int(values[6])
    except ValueError as exc:
        raise MigrationError(
            f"{values[0]} 的 retry_count 不是整数：{values[6]!r}"
        ) from exc
    return TaskRow(
        table=values[0],
        task_type=task_type,
        biz_id=biz_id,
        action_time=require_datetime(values[3], f"{values[0]} action_time"),
        next_fire_time=require_datetime(values[4], f"{values[0]} next_fire_time"),
        status=values[5],
        retry_count=retry_count,
        created_at=require_datetime(values[7], f"{values[0]} created_at"),
        updated_at=require_datetime(values[8], f"{values[0]} updated_at"),
        origin=origin,
    )


def fetch_rows(
    client: SqlClient,
    schema: str,
    tables: Sequence[str],
    statuses: Sequence[str],
    origin: str,
) -> list[TaskRow]:
    if not tables:
        return []
    rows = client.query(_select_rows_sql(schema, tables, statuses))
    return [task_row_from_values(values, origin) for values in rows]


def outbox_exists(client: SqlClient, schema: str) -> bool:
    rows = client.query(
        "SELECT COUNT(*) FROM information_schema.tables "
        f"WHERE table_schema = {sql_text(schema)} AND table_name = {sql_text(OUTBOX_TABLE)}"
    )
    return bool(rows and int(rows[0][0]) > 0)


def select_outbox_sql(schema: str) -> str:
    """outbox 的列名是 `type/state/create_time/update_time`（不是分表的 `task_type/status/...`）。

    票 03 删表前的 DDL 与 `TimeoutRegisterOutboxMapper.xml` 都以此为准；写错列名会让
    `forward` 在真实生产库上直接报 Unknown column，而不是搬错数据。
    """
    return (
        "SELECT HEX(type), HEX(biz_id), "
        "DATE_FORMAT(action_time, '%Y-%m-%d %H:%i:%s'), "
        "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s'), "
        "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') "
        f"FROM {qname(schema, OUTBOX_TABLE)} WHERE state = 'PENDING'"
    )


def fetch_outbox_rows(client: SqlClient, schema: str) -> list[TaskRow]:
    rows = client.query(select_outbox_sql(schema))
    result: list[TaskRow] = []
    for values in rows:
        if len(values) != 5:
            raise MigrationError(f"outbox 查询列数不符：{values!r}")
        try:
            task_type = bytes.fromhex(values[0]).decode("utf-8")
            biz_id = bytes.fromhex(values[1]).decode("utf-8")
        except (ValueError, UnicodeDecodeError) as exc:
            raise MigrationError(f"无法解码 outbox 行：{values!r}") from exc
        # outbox 的 action_time 就是中心的到期时刻；new code 注册时 next_fire_time = action_time
        result.append(
            TaskRow(
                table=OUTBOX_TABLE,
                task_type=task_type,
                biz_id=biz_id,
                action_time=require_datetime(values[2], f"{OUTBOX_TABLE} action_time"),
                next_fire_time=require_datetime(values[2], f"{OUTBOX_TABLE} action_time"),
                status="WAITING",
                retry_count=0,
                created_at=require_datetime(values[3], f"{OUTBOX_TABLE} created_at"),
                updated_at=require_datetime(values[4], f"{OUTBOX_TABLE} updated_at"),
                origin="outbox",
            )
        )
    return result


def fetch_source_rows_by_key(
    client: SqlClient, schema: str, keys: Iterable[tuple[str, str]]
) -> dict[tuple[str, str], TaskRow]:
    """查旧中心这些键的完整行（含 SUCCESS/CANCELED 等终态）。

    `forward` 只搬非终态行，但 outbox 冲突不能只看「有没有行」：outbox 的 `uk(type,biz_id)`
    upsert 会把同一行复位成 `PENDING`，所以 PENDING 也可能是**新一轮注册**（旧中心会把终态行
    复活）。必须拿到旧行的状态与 `action_time` 才能区分「陈旧残留」与「新意图」。
    """
    unique = list(dict.fromkeys(keys))
    result: dict[tuple[str, str], TaskRow] = {}
    for start in range(0, len(unique), SOURCE_KEY_CHUNK):
        chunk = unique[start:start + SOURCE_KEY_CHUNK]
        pairs = ", ".join(
            f"({sql_text(task_type)}, {sql_text(biz_id)})" for task_type, biz_id in chunk
        )
        selects = [
            f"SELECT {sql_text(table)}, HEX(task_type), HEX(biz_id), "
            "DATE_FORMAT(action_time, '%Y-%m-%d %H:%i:%s'), "
            "DATE_FORMAT(next_fire_time, '%Y-%m-%d %H:%i:%s'), "
            "status, retry_count, "
            "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s'), "
            "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') "
            f"FROM {qname(schema, table)} WHERE (task_type, biz_id) IN ({pairs})"
            for table in timeout_table_names()
        ]
        for values in client.query("\nUNION ALL\n".join(selects)):
            row = task_row_from_values(values, "center")
            result[row.key] = row
    return result


# ---------------------------------------------------------------------------
# 计划（纯函数，便于单测）
# ---------------------------------------------------------------------------

def outbox_is_new_registration(outbox_row: TaskRow, center_row: TaskRow) -> bool:
    """PENDING outbox 是否代表「新一轮注册」而不是陈旧残留（同键 upsert 会复位 PENDING）。

    旧中心的注册语义：在途同键不改期、终态同键复活。所以 outbox 的 `action_time` 比旧中心行更晚
    时，它只可能是新注册；否则视为发送失败的残留，按旧中心为准。
    """
    return outbox_row.action_time > center_row.action_time


def plan_forward(
    source_rows: Sequence[TaskRow],
    outbox_rows: Sequence[TaskRow],
    target_rows: Sequence[TaskRow],
    source_by_key: dict[tuple[str, str], TaskRow] | None = None,
) -> ForwardPlan:
    plan = ForwardPlan(source_rows=list(source_rows), outbox_rows=list(outbox_rows))
    existing_targets = {row.key: row for row in target_rows}
    seen_source: dict[tuple[str, str], TaskRow] = {}

    for row in source_rows:
        previous = seen_source.get(row.key)
        if previous is not None:
            raise MigrationError(
                "源库存在重复 (task_type, biz_id)，先人工核对："
                f"{row.key!r} 同时在 {previous.table} 与 {row.table}"
            )
        seen_source[row.key] = row

    # 默认只认识待迁移的非终态行；真实调用会额外传入终态行（见 fetch_source_rows_by_key）。
    known_source = dict(source_by_key) if source_by_key is not None else {
        row.key: row for row in source_rows
    }

    candidates: list[TaskRow] = list(source_rows)
    for row in outbox_rows:
        old = known_source.get(row.key)
        if old is None:
            candidates.append(row)
        elif old.status in ACTIVE_STATUSES:
            plan.skips.append(
                PlannedSkip(
                    row,
                    None,
                    f"outbox 行已在旧中心在途（status={old.status}），以中心行为准",
                )
            )
        elif outbox_is_new_registration(row, old):
            # 旧中心会复活终态行；这是 legit 的新一轮注册，不能当残留丢掉。
            candidates.append(row)
        else:
            plan.skips.append(
                PlannedSkip(
                    row,
                    None,
                    f"旧中心同键已是终态（status={old.status}）且 outbox 不更新，按残留跳过",
                )
            )

    for row in candidates:
        target = existing_targets.get(row.key)
        if target is not None:
            plan.skips.append(
                PlannedSkip(
                    row,
                    target.table,
                    f"目标已有同键行（status={target.status}），保留目标行",
                )
            )
            continue
        plan.inserts.append(PlannedInsert(physical_table(new_slot(row.biz_id)), row))

    return plan


def plan_reverse(
    target_rows: Sequence[TaskRow],
    old_rows: Sequence[TaskRow],
    cancel_stale_old: bool = True,
) -> ReversePlan:
    plan = ReversePlan(target_rows=list(target_rows))
    existing_old = {row.key: row for row in old_rows}

    for row in target_rows:
        old = existing_old.get(row.key)
        if row.status in ACTIVE_STATUSES:
            if old is None:
                plan.inserts.append(PlannedInsert(physical_table(old_slot(row.task_type, row.biz_id)), row))
            elif old.status in ACTIVE_STATUSES:
                plan.skips.append(
                    PlannedSkip(row, old.table, f"旧中心已有在途行（status={old.status}）")
                )
            else:
                plan.revives.append(
                    PlannedInsert(physical_table(old_slot(row.task_type, row.biz_id)), row)
                )
        elif row.status == "FAILED":
            if old is None:
                plan.inserts.append(PlannedInsert(physical_table(old_slot(row.task_type, row.biz_id)), row))
            elif old.status == "FAILED":
                plan.skips.append(PlannedSkip(row, old.table, "旧中心已有同键死信"))
            elif old.status in ACTIVE_STATUSES:
                plan.skips.append(
                    PlannedSkip(row, old.table, "旧中心在途行继续重试（不用新死信覆盖）")
                )
            else:
                plan.skips.append(
                    PlannedSkip(
                        row,
                        old.table,
                        f"旧中心同键已是终态（status={old.status}）；业务已结算，保留旧终态",
                    )
                )
        elif row.status in ("SUCCESS", "CANCELED"):
            if old is not None and old.status in ACTIVE_STATUSES and cancel_stale_old:
                plan.cancels.append(
                    PlannedInsert(physical_table(old_slot(row.task_type, row.biz_id)), row)
                )
            else:
                plan.skips.append(PlannedSkip(row, old.table if old else None, "无需逆向的终态行"))
        else:
            plan.skips.append(PlannedSkip(row, old.table if old else None, f"未知状态 {row.status}"))

    return plan


# ---------------------------------------------------------------------------
# SQL 生成
# ---------------------------------------------------------------------------

def insert_forward_sql(cfg: Config, item: PlannedInsert) -> str:
    row = item.row
    return (
        f"INSERT INTO {qname(cfg.target_schema, item.target_table)} "
        "(task_type, biz_id, action_time, next_fire_time, status, retry_count, created_at, updated_at) "
        "VALUES ("
        f"{sql_text(row.task_type)}, {sql_text(row.biz_id)}, "
        f"{sql_datetime(row.action_time)}, {sql_datetime(row.next_fire_time)}, "
        f"{sql_text(row.status)}, {row.retry_count}, "
        f"{sql_datetime(row.created_at)}, {sql_datetime(row.updated_at)});"
    )


def insert_reverse_sql(cfg: Config, item: PlannedInsert) -> str:
    row = item.row
    return (
        f"INSERT INTO {qname(cfg.source_schema, item.target_table)} "
        "(task_type, callback_group, biz_id, action_time, next_fire_time, status, retry_count, created_at, updated_at) "
        "VALUES ("
        f"{sql_text(row.task_type)}, {sql_text(cfg.callback_group)}, {sql_text(row.biz_id)}, "
        f"{sql_datetime(row.action_time)}, {sql_datetime(row.next_fire_time)}, "
        f"{sql_text(row.status)}, {row.retry_count}, "
        f"{sql_datetime(row.created_at)}, {sql_datetime(row.updated_at)});"
    )


def revive_reverse_sql(cfg: Config, item: PlannedInsert) -> str:
    row = item.row
    return (
        f"UPDATE {qname(cfg.source_schema, item.target_table)} SET "
        f"status = {sql_text(row.status)}, "
        f"action_time = {sql_datetime(row.action_time)}, "
        f"next_fire_time = {sql_datetime(row.next_fire_time)}, "
        f"retry_count = {row.retry_count}, "
        f"updated_at = {sql_datetime(row.updated_at)} "
        f"WHERE task_type = {sql_text(row.task_type)} AND biz_id = {sql_text(row.biz_id)} "
        "AND status IN ('SUCCESS', 'FAILED', 'CANCELED');"
    )


def cancel_reverse_sql(cfg: Config, item: PlannedInsert) -> str:
    row = item.row
    return (
        f"UPDATE {qname(cfg.source_schema, item.target_table)} SET "
        "status = 'CANCELED', updated_at = NOW() "
        f"WHERE task_type = {sql_text(row.task_type)} AND biz_id = {sql_text(row.biz_id)} "
        "AND status IN ('WAITING', 'RETRYING');"
    )


def forward_script(cfg: Config, plan: ForwardPlan) -> str:
    statements = ["SET NAMES utf8mb4;", "START TRANSACTION;"]
    statements.extend(insert_forward_sql(cfg, item) for item in plan.inserts)
    statements.append("COMMIT;")
    return "\n".join(statements) + "\n"


def reverse_script(cfg: Config, plan: ReversePlan) -> str:
    statements = ["SET NAMES utf8mb4;", "START TRANSACTION;"]
    statements.extend(insert_reverse_sql(cfg, item) for item in plan.inserts)
    statements.extend(revive_reverse_sql(cfg, item) for item in plan.revives)
    statements.extend(cancel_reverse_sql(cfg, item) for item in plan.cancels)
    statements.append("COMMIT;")
    return "\n".join(statements) + "\n"


# ---------------------------------------------------------------------------
# 校验
# ---------------------------------------------------------------------------

def verify_forward(
    cfg: Config,
    plan: ForwardPlan,
    target_rows: Sequence[TaskRow],
) -> list[str]:
    errors: list[str] = []
    target_by_key = {row.key: row for row in target_rows}

    # 源行必须都在目标库；outbox 行只有「本次插入」或「目标已有同键」才要求出现——
    # 被旧中心终态行挡掉的 outbox 残留本来就不该出现在目标库。
    expected_keys = {row.key for row in plan.source_rows}
    expected_keys |= {item.row.key for item in plan.inserts}
    expected_keys |= {row.key for row in plan.outbox_rows if row.key in target_by_key}
    for key in expected_keys:
        if key not in target_by_key:
            errors.append(f"源行未出现在目标库：task_type={key[0]!r}, biz_id={key[1]!r}")

    for item in plan.inserts:
        actual = target_by_key.get(item.row.key)
        if actual is None:
            errors.append(f"本次插入的行未读到：{item.row.key!r} -> {item.target_table}")
            continue
        if actual.table != item.target_table:
            errors.append(
                f"插入落表不符：{item.row.key!r} 预期 {item.target_table}，实际 {actual.table}"
            )
        if actual.status != item.row.status:
            errors.append(
                f"状态不符：{item.row.key!r} 预期 {item.row.status}，实际 {actual.status}"
            )
        if actual.action_time != item.row.action_time:
            errors.append(
                f"action_time 不符：{item.row.key!r} 预期 {item.row.action_time}，"
                f"实际 {actual.action_time}"
            )

    # 全量不变量：目标表每一行的物理表都必须等于新槽位。错表 = 扫描器永远看不到。
    for row in target_rows:
        expected_table = physical_table(new_slot(row.biz_id))
        if row.table != expected_table:
            errors.append(
                f"目标行槽位错误（扫描器会漏掉）：{row.key!r} 在 {row.table}，应在 {expected_table}"
            )

    return errors


def verify_reverse(
    cfg: Config,
    plan: ReversePlan,
    old_rows: Sequence[TaskRow],
    target_rows: Sequence[TaskRow],
) -> list[str]:
    errors: list[str] = []
    old_by_key = {row.key: row for row in old_rows}
    target_by_key = {row.key: row for row in target_rows}
    expected_tables = {
        row.key: physical_table(old_slot(row.task_type, row.biz_id))
        for row in target_rows
    }

    accounted = set()
    for item in plan.inserts + plan.revives:
        accounted.add(item.row.key)
        actual = old_by_key.get(item.row.key)
        if actual is None:
            errors.append(f"逆向搬迁的行未读到：{item.row.key!r} -> {item.target_table}")
            continue
        if actual.table != item.target_table:
            errors.append(
                f"逆向落表不符：{item.row.key!r} 预期 {item.target_table}，实际 {actual.table}"
            )
        if actual.status != item.row.status:
            errors.append(
                f"逆向状态不符：{item.row.key!r} 预期 {item.row.status}，实际 {actual.status}"
            )
        if actual.action_time != item.row.action_time:
            errors.append(
                f"逆向 action_time 不符：{item.row.key!r} 预期 {item.row.action_time}，"
                f"实际 {actual.action_time}"
            )

    for item in plan.cancels:
        actual = old_by_key.get(item.row.key)
        if actual is None:
            errors.append(f"取消陈旧旧行失败（行不存在）：{item.row.key!r}")
        elif actual.status != "CANCELED":
            errors.append(
                f"旧行未置取消：{item.row.key!r} 实际 status={actual.status}（新中心已终态）"
            )

    # 旧中心的行必须按旧公式落表；否则旧扫描器漏行。
    for key, expected_table in expected_tables.items():
        old = old_by_key.get(key)
        if old is not None and old.table != expected_table:
            errors.append(
                f"旧中心行槽位错误（旧扫描器会漏掉）：{key!r} 在 {old.table}，应在 {expected_table}"
            )

    for row in target_rows:
        if row.status in ACTIVE_STATUSES or row.status == "FAILED":
            if row.key not in accounted and row.key not in old_by_key:
                errors.append(f"目标行未逆向到旧中心：{row.key!r}（status={row.status}）")

    return errors


# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------

def count_by_status(rows: Iterable[TaskRow]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in rows:
        counts[row.status] = counts.get(row.status, 0) + 1
    return dict(sorted(counts.items()))


def count_by_table(rows: Iterable[TaskRow]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in rows:
        counts[row.table] = counts.get(row.table, 0) + 1
    return dict(sorted(counts.items()))


def build_report(
    mode: str,
    cfg: Config,
    *,
    warnings: Sequence[str] = (),
    summary: dict | None = None,
    conflicts: Sequence[PlannedSkip] = (),
    verification_errors: Sequence[str] = (),
) -> dict:
    return {
        "script_version": SCRIPT_VERSION,
        "mode": mode,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "dry_run": cfg.dry_run,
        "source_schema": cfg.source_schema,
        "target_schema": cfg.target_schema,
        "counts": summary or {},
        "warnings": list(warnings),
        "conflicts": [
            {
                "origin": skip.row.origin,
                "source_table": skip.row.table,
                "target_table": skip.target_table,
                "task_type": skip.row.task_type,
                "biz_id": skip.row.biz_id,
                "status": skip.row.status,
                "reason": skip.reason,
            }
            for skip in list(conflicts)[:200]
        ],
        "conflict_total": len(conflicts),
        "verification": {
            "ok": not verification_errors,
            "errors": list(verification_errors),
        },
    }


def write_report(path: str | None, report: dict) -> None:
    if not path:
        return
    directory = os.path.dirname(os.path.abspath(path))
    try:
        if directory:
            os.makedirs(directory, exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    except OSError as exc:
        # 报告是辅助产物；写失败不应把已提交的搬迁误报成失败（退出码仍由校验决定）。
        print(f"[warn] 报告写入失败（{path}）：{exc}", file=sys.stderr)


# ---------------------------------------------------------------------------
# 命令
# ---------------------------------------------------------------------------

def print_preflight(cfg: Config, client: SqlClient) -> list[str]:
    warnings: list[str] = []

    ensure_timeout_tables(client, cfg.source_schema, "源库")
    ensure_timeout_tables(
        client, cfg.target_schema, "目标库", "（先执行 better-school/sql/schema.sql）"
    )

    target_count = sum(table_count(client, cfg.target_schema, table) for table in timeout_table_names())
    if target_count > 0 and not cfg.allow_existing_target:
        raise MigrationError(
            f"目标库已有 {target_count} 行超时任务。滚动上线后这是正常的（新代码先写入），"
            "确认后加 --allow-existing-target 再跑；否则请先核对 schema 是否指错。"
        )
    if target_count > 0:
        warnings.append(f"目标库已有 {target_count} 行（同键以目标行为准，只补缺不覆盖）")

    statuses = job_statuses(client, cfg.xxl_schema)
    warnings.extend(check_job_statuses(cfg, statuses, "forward"))

    if outbox_exists(client, cfg.target_schema):
        # COUNT(*) 是全表；PENDING 行数单独查
        rows = client.query(
            f"SELECT COUNT(*) FROM {qname(cfg.target_schema, OUTBOX_TABLE)} WHERE state = 'PENDING'"
        )
        pending = int(rows[0][0]) if rows else 0
        warnings.append(f"{OUTBOX_TABLE} 存在，PENDING={pending}（会一并搬入）")
    else:
        warnings.append(f"{OUTBOX_TABLE} 不存在（已清理或全新库），跳过 outbox 搬迁")

    return warnings


def read_all_for_forward(
    cfg: Config, client: SqlClient
) -> tuple[list[TaskRow], list[TaskRow], list[TaskRow], dict[tuple[str, str], TaskRow]]:
    source_rows = fetch_rows(
        client, cfg.source_schema, timeout_table_names(), MIGRATED_STATUSES, "center"
    )
    outbox_rows = (
        fetch_outbox_rows(client, cfg.target_schema)
        if outbox_exists(client, cfg.target_schema)
        else []
    )
    source_by_key = {row.key: row for row in source_rows}
    missing_keys = [row.key for row in outbox_rows if row.key not in source_by_key]
    if missing_keys:
        source_by_key.update(
            fetch_source_rows_by_key(client, cfg.source_schema, missing_keys)
        )
    target_rows = fetch_rows(
        client,
        cfg.target_schema,
        timeout_table_names(),
        ALL_STATUSES,
        "target",
    )
    return source_rows, outbox_rows, target_rows, source_by_key


def command_forward(cfg: Config) -> int:
    client = SqlClient(cfg.mysql_bin, cfg.host, cfg.port, cfg.user, cfg.password)
    warnings = print_preflight(cfg, client)

    source_rows, outbox_rows, target_rows, source_by_key = read_all_for_forward(cfg, client)
    plan = plan_forward(source_rows, outbox_rows, target_rows, source_by_key)

    summary = {
        "source_rows": len(source_rows),
        "source_by_status": count_by_status(source_rows),
        "outbox_rows": len(outbox_rows),
        "target_rows_before": len(target_rows),
        "planned_inserts": len(plan.inserts),
        "planned_inserts_by_status": count_by_status(item.row for item in plan.inserts),
        "planned_inserts_by_table": count_by_table(item.row for item in plan.inserts),
        "skipped": len(plan.skips),
    }

    print(
        f"[forward] 源 {len(source_rows)} 行 {summary['source_by_status']}，"
        f"outbox PENDING {len(outbox_rows)} 行，目标现有 {len(target_rows)} 行"
    )
    print(
        f"[forward] 计划插入 {len(plan.inserts)} 行，跳过 {len(plan.skips)} 行"
        + ("（dry-run，不写库）" if cfg.dry_run else "")
    )

    if plan.inserts and not cfg.dry_run:
        client.run(forward_script(cfg, plan))
        try:
            target_rows_after = fetch_rows(
                client,
                cfg.target_schema,
                timeout_table_names(),
                ALL_STATUSES,
                "target",
            )
        except MigrationError as exc:
            print(f"[error] 搬迁事务已提交，但校验查询失败：{exc}", file=sys.stderr)
            print("[forward] 数据可能已写入；重跑安全（只增不改），先人工核对目标表", file=sys.stderr)
            return EXIT_VERIFY
    else:
        target_rows_after = target_rows

    verification_errors = (
        [] if cfg.dry_run else verify_forward(cfg, plan, target_rows_after)
    )
    summary["target_rows_after"] = len(target_rows_after)
    summary["target_by_status_after"] = count_by_status(target_rows_after)

    report = build_report(
        "forward",
        cfg,
        warnings=warnings,
        summary=summary,
        conflicts=plan.skips,
        verification_errors=verification_errors,
    )
    write_report(cfg.report_path, report)

    for warning in warnings:
        print(f"[warn] {warning}")
    for error in verification_errors:
        print(f"[verify] {error}", file=sys.stderr)
    if verification_errors:
        print(f"[forward] 校验失败：{len(verification_errors)} 项", file=sys.stderr)
        return EXIT_VERIFY

    print("[forward] 完成" + ("（dry-run：未写库，未校验落库）" if cfg.dry_run else "且校验通过"))
    return EXIT_OK


def command_reverse(cfg: Config) -> int:
    client = SqlClient(cfg.mysql_bin, cfg.host, cfg.port, cfg.user, cfg.password)
    warnings: list[str] = []

    ensure_timeout_tables(client, cfg.source_schema, "源库")
    ensure_timeout_tables(client, cfg.target_schema, "目标库")

    statuses = job_statuses(client, cfg.xxl_schema)
    warnings.extend(check_job_statuses(cfg, statuses, "reverse"))

    target_rows = fetch_rows(
        client,
        cfg.target_schema,
        timeout_table_names(),
        ALL_STATUSES,
        "target",
    )
    old_rows = fetch_rows(
        client,
        cfg.source_schema,
        timeout_table_names(),
        ALL_STATUSES,
        "center",
    )
    plan = plan_reverse(target_rows, old_rows, cancel_stale_old=cfg.cancel_stale_old)

    summary = {
        "target_rows": len(target_rows),
        "target_by_status": count_by_status(target_rows),
        "old_rows_before": len(old_rows),
        "planned_inserts": len(plan.inserts),
        "planned_revives": len(plan.revives),
        "planned_cancels": len(plan.cancels),
        "skipped": len(plan.skips),
    }
    print(
        f"[reverse] 新库 {len(target_rows)} 行 {summary['target_by_status']}，"
        f"旧库现有 {len(old_rows)} 行"
    )
    print(
        f"[reverse] 计划插入 {len(plan.inserts)}、复活 {len(plan.revives)}、"
        f"取消陈旧旧行 {len(plan.cancels)}、跳过 {len(plan.skips)}"
        + ("（dry-run，不写库）" if cfg.dry_run else "")
    )

    if (plan.inserts or plan.revives or plan.cancels) and not cfg.dry_run:
        client.run(reverse_script(cfg, plan))
        try:
            old_rows_after = fetch_rows(
                client,
                cfg.source_schema,
                timeout_table_names(),
                ALL_STATUSES,
                "center",
            )
        except MigrationError as exc:
            print(f"[error] 回滚事务已提交，但校验查询失败：{exc}", file=sys.stderr)
            print("[reverse] 数据可能已写回旧库；重跑安全（只增不改），先人工核对旧表", file=sys.stderr)
            return EXIT_VERIFY
    else:
        old_rows_after = old_rows

    verification_errors = (
        [] if cfg.dry_run else verify_reverse(cfg, plan, old_rows_after, target_rows)
    )
    summary["old_rows_after"] = len(old_rows_after)
    summary["old_by_status_after"] = count_by_status(old_rows_after)

    report = build_report(
        "reverse",
        cfg,
        warnings=warnings,
        summary=summary,
        conflicts=plan.skips,
        verification_errors=verification_errors,
    )
    write_report(cfg.report_path, report)

    for warning in warnings:
        print(f"[warn] {warning}")
    for error in verification_errors:
        print(f"[verify] {error}", file=sys.stderr)
    if verification_errors:
        print(f"[reverse] 校验失败：{len(verification_errors)} 项", file=sys.stderr)
        return EXIT_VERIFY

    print("[reverse] 完成" + ("（dry-run：未写库，未校验落库）" if cfg.dry_run else "且校验通过"))
    return EXIT_OK


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="超时中心内嵌化：bs_timeout <-> better_school 的一次性数据搬迁（票 07）",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    subparsers = parser.add_subparsers(dest="mode", required=True)

    def add_common(sub: argparse.ArgumentParser) -> None:
        sub.add_argument("--mysql", default=os.environ.get("MYSQL_BIN", "mysql"),
                         help="mysql 客户端路径")
        sub.add_argument("--host", default=os.environ.get("MYSQL_HOST", "127.0.0.1"))
        sub.add_argument("--port", type=int, default=int(os.environ.get("MYSQL_TCP_PORT", "3306")))
        sub.add_argument("--user", default=os.environ.get("MYSQL_USER", "root"))
        sub.add_argument("--password", default=os.environ.get("MYSQL_PWD"),
                         help="不传则沿用 MYSQL_PWD 环境变量")
        sub.add_argument("--source-schema", default="bs_timeout",
                         help="旧中心库（forward 的源 / reverse 的目标）")
        sub.add_argument("--target-schema", default="better_school",
                         help="业务库（forward 的目标 / reverse 的源）")
        sub.add_argument("--xxl-schema", default="xxl_job",
                         help="xxl-job admin 库（用于检查扫描 job 是否已停）")
        sub.add_argument("--callback-group", default="better-school",
                         help="仅 reverse 用：旧中心的回调 group（= 单体应用名）")
        sub.add_argument("--dry-run", action="store_true", help="只打印计划，不写库")
        sub.add_argument("--force", action="store_true",
                         help="越过扫描 job 状态的保护（慎用，会记入报告）")
        sub.add_argument("--allow-existing-target", action="store_true",
                         help="forward 时允许目标库已有行（新代码已滚动上线时必然如此）")
        sub.add_argument("--report", dest="report_path", default=None,
                         help="把预检/计划/校验结果写成 JSON 报告")

    forward = subparsers.add_parser("forward", help="旧中心 -> 业务库")
    add_common(forward)

    reverse = subparsers.add_parser("reverse", help="业务库 -> 旧中心（回滚）")
    add_common(reverse)
    reverse.add_argument("--keep-stale-old", dest="cancel_stale_old", action="store_false",
                         default=True,
                         help="不把「新中心已终态、旧中心仍存活」的旧行置取消（默认会取消）")

    return parser


def config_from_args(args: argparse.Namespace) -> Config:
    return Config(
        mysql_bin=args.mysql,
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        source_schema=args.source_schema,
        target_schema=args.target_schema,
        xxl_schema=args.xxl_schema,
        callback_group=args.callback_group,
        dry_run=args.dry_run,
        force=args.force,
        allow_existing_target=args.allow_existing_target,
        cancel_stale_old=getattr(args, "cancel_stale_old", True),
        report_path=args.report_path,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    cfg = config_from_args(args)
    try:
        if args.mode == "forward":
            return command_forward(cfg)
        if args.mode == "reverse":
            return command_reverse(cfg)
        parser.error(f"未知模式：{args.mode}")
        return EXIT_PREFLIGHT
    except MigrationError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return EXIT_PREFLIGHT


if __name__ == "__main__":
    sys.exit(main())
