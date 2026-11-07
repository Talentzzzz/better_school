#!/usr/bin/env python3
"""票 07 迁移工具的干净 E2E 驱动（临时脚本，不入库）。

用本机 3307 的 scratch MySQL 重建三个 schema，按真实 DDL（含 outbox 的
type/state/create_time/update_time 列名）seed，然后跑通：

  * 旧 job 启用 -> forward 预检退出码 2
  * 新 job 启用 -> forward 预检退出码 2
  * dry-run -> forward 正式 -> 校验 ok -> 重跑幂等
  * reverse dry-run -> 正式 -> 校验 ok（含 revive / 陈旧旧行取消）
  * 目标行放错分表 -> forward 校验退出码 3

运行：python .scratch/timeout-inline/probe/e2e_cutover.py
"""

from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MIGRATE_PATH = ROOT / "backend" / "scripts" / "timeout-cutover-migrate.py"
SPEC = importlib.util.spec_from_file_location("timeout_cutover_migrate", MIGRATE_PATH)
migrate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = migrate
SPEC.loader.exec_module(migrate)

MYSQL = r"C:\Code\MySQL\MySQL Server 8.0\bin\mysql.exe"
HOST, PORT, USER = "127.0.0.1", "3307", "root"
SRC, DST, XXL = "cut2_src", "cut2_dst", "cut2_xxl"
REPORT = Path(__file__).with_name("e2e-report.json")

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

TYPES = {
    "pay": "PRODUCT_ORDER_PAY_TIMEOUT",
    "errand_pay": "ERRAND_PAY_TIMEOUT",
    "lock": "ERRAND_LOCK_TIMEOUT",
    "review": "REVIEW_LEASE_TIMEOUT",
}


def sql(statement: str, schema: str | None = None) -> str:
    command = [MYSQL, "--default-character-set=utf8mb4", "--batch", "--skip-column-names",
               f"--host={HOST}", f"--port={PORT}", f"--user={USER}"]
    if schema:
        command.append(schema)
    done = subprocess.run(command, input=statement.encode("utf-8"),
                          capture_output=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.decode("utf-8", "replace"))
    return done.stdout.decode("utf-8", "replace")


def query(statement: str, schema: str | None = None) -> list[list[str]]:
    output = sql(statement, schema)
    return [line.split("\t") for line in output.splitlines() if line.strip()]


def value(statement: str, schema: str | None = None) -> str:
    rows = query(statement, schema)
    return rows[0][0] if rows else ""


def q(schema: str, table: str) -> str:
    return f"`{schema}`.`{table}`"


def literal(text: str) -> str:
    return "CONVERT(X'" + text.encode("utf-8").hex() + "' USING utf8mb4)"


def table(row_slot: int) -> str:
    return f"timeout_task_{row_slot:02d}"


def create_schemas() -> None:
    for schema in (SRC, DST, XXL):
        sql(f"DROP DATABASE IF EXISTS `{schema}`; CREATE DATABASE `{schema}` "
            "DEFAULT CHARACTER SET utf8mb4;")


def create_old_tables() -> None:
    ddl = (ROOT / "backend" / "timeout-center" / "sql" / "schema-timeout-task.sql").read_text(
        encoding="utf-8")
    sql(ddl, SRC)


def create_new_tables() -> None:
    schema_sql = (ROOT / "backend" / "better-school" / "sql" / "schema.sql").read_text(
        encoding="utf-8")
    blocks = re.findall(
        r"CREATE TABLE IF NOT EXISTS timeout_task_\d\d \(.*?\) ENGINE = InnoDB[^;]*;",
        schema_sql, re.S)
    assert len(blocks) == 16, len(blocks)
    sql("\n".join(blocks), DST)
    sql(
        """
        CREATE TABLE IF NOT EXISTS timeout_register_outbox (
            id             BIGINT      NOT NULL AUTO_INCREMENT,
            type           VARCHAR(64) NOT NULL,
            callback_group VARCHAR(64) NOT NULL,
            biz_id         VARCHAR(64) NOT NULL,
            action_time    DATETIME    NOT NULL,
            state          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
            retry_count    INT         NOT NULL DEFAULT 0,
            create_time    DATETIME    NOT NULL,
            update_time    DATETIME    NOT NULL,
            PRIMARY KEY (id),
            UNIQUE KEY uk_type_biz (type, biz_id),
            KEY idx_scan (state, create_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
        """,
        DST,
    )


def create_xxl() -> None:
    sql(
        """
        CREATE TABLE IF NOT EXISTS xxl_job_info (
            id             INT NOT NULL PRIMARY KEY,
            job_group      INT NOT NULL,
            executor_handler VARCHAR(128) NOT NULL,
            trigger_status TINYINT NOT NULL
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
        INSERT INTO xxl_job_info VALUES
            (1, 1, 'timeoutTaskScanJob', 0),
            (2, 2, 'timeoutTaskScan', 0);
        """,
        XXL,
    )


def seed_old(rows: list[dict]) -> None:
    statements = []
    for item in rows:
        slot = migrate.old_slot(item["task_type"], item["biz_id"])
        statements.append(
            f"INSERT INTO {q(SRC, table(slot))} "
            "(task_type, callback_group, biz_id, action_time, next_fire_time, status, "
            "retry_count, created_at, updated_at) VALUES ("
            f"{literal(item['task_type'])}, 'better-school', {literal(item['biz_id'])}, "
            f"'{item['action_time']}', '{item['action_time']}', "
            f"'{item['status']}', {item.get('retry_count', 0)}, "
            f"'{item['created_at']}', '{item['updated_at']}');"
        )
    sql("\n".join(statements), SRC)


def seed_target(rows: list[dict]) -> None:
    statements = []
    for item in rows:
        slot = migrate.new_slot(item["biz_id"])
        statements.append(
            f"INSERT INTO {q(DST, table(slot))} "
            "(task_type, biz_id, action_time, next_fire_time, status, retry_count, "
            "created_at, updated_at) VALUES ("
            f"{literal(item['task_type'])}, {literal(item['biz_id'])}, "
            f"'{item['action_time']}', '{item['action_time']}', "
            f"'{item['status']}', {item.get('retry_count', 0)}, "
            f"'{item['created_at']}', '{item['updated_at']}');"
        )
    sql("\n".join(statements), DST)


def seed_outbox(rows: list[dict]) -> None:
    statements = []
    for item in rows:
        statements.append(
            "INSERT INTO " + q(DST, "timeout_register_outbox") +
            " (type, callback_group, biz_id, action_time, state, retry_count, create_time, update_time) VALUES ("
            f"{literal(item['task_type'])}, 'better-school', {literal(item['biz_id'])}, "
            f"'{item['action_time']}', '{item['status']}', 0, "
            f"'{item['created_at']}', '{item['updated_at']}');"
        )
    sql("\n".join(statements), DST)


def run_tool(mode: str, *extra: str) -> tuple[int, dict]:
    command = [
        sys.executable, str(MIGRATE_PATH), mode,
        f"--mysql={MYSQL}", f"--host={HOST}", f"--port={PORT}", f"--user={USER}",
        f"--source-schema={SRC}", f"--target-schema={DST}", f"--xxl-schema={XXL}",
        f"--report={REPORT}",
        *extra,
    ]
    done = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                          errors="replace")
    print(f"\n$ {' '.join(command[1:])}")
    print(done.stdout.strip())
    if done.stderr.strip():
        print(done.stderr.strip())
    report = json.loads(REPORT.read_text(encoding="utf-8")) if REPORT.exists() else {}
    return done.returncode, report


def set_job(handler: str, status: int) -> None:
    sql(f"UPDATE xxl_job_info SET trigger_status = {status} "
        f"WHERE executor_handler = '{handler}';", XXL)


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)
    print(f"  ok - {message}")


def main() -> int:
    create_schemas()
    create_old_tables()
    create_new_tables()
    create_xxl()

    source = [
        dict(task_type=TYPES["pay"], biz_id="1001", status="WAITING",
             action_time="2026-10-17 12:00:00"),
        dict(task_type=TYPES["errand_pay"], biz_id="2002", status="RETRYING",
             action_time="2026-10-17 12:10:00", retry_count=3),
        dict(task_type=TYPES["lock"], biz_id="3003:1", status="WAITING",
             action_time="2026-10-17 12:20:00"),
        dict(task_type=TYPES["review"], biz_id="4004:2", status="FAILED",
             action_time="2026-10-17 12:30:00", retry_count=16),
        dict(task_type=TYPES["pay"], biz_id="订单-测试", status="WAITING",
             action_time="2026-10-17 12:40:00"),
        dict(task_type=TYPES["lock"], biz_id="😀:7", status="SUCCESS",
             action_time="2026-10-17 12:50:00"),
        dict(task_type=TYPES["errand_pay"], biz_id="2003", status="CANCELED",
             action_time="2026-10-17 12:55:00"),
    ]
    for item in source:
        item.setdefault("created_at", "2026-10-17 11:45:00")
        item.setdefault("updated_at", "2026-10-17 11:45:00")
    seed_old(source)

    target_preexisting = [
        dict(task_type=TYPES["pay"], biz_id="1001", status="RETRYING", retry_count=2,
             action_time="2026-10-17 12:00:00"),
        dict(task_type=TYPES["pay"], biz_id="8008", status="WAITING",
             action_time="2026-10-17 14:00:00"),
    ]
    for item in target_preexisting:
        item.setdefault("created_at", "2026-10-17 13:00:00")
        item.setdefault("updated_at", "2026-10-17 13:00:00")
    seed_target(target_preexisting)

    seed_outbox([
        dict(task_type=TYPES["pay"], biz_id="5005", status="PENDING",
             action_time="2026-10-17 13:30:00", created_at="2026-10-17 13:10:00",
             updated_at="2026-10-17 13:10:00"),
        dict(task_type=TYPES["errand_pay"], biz_id="2002", status="PENDING",
             action_time="2026-10-17 12:10:00", created_at="2026-10-17 11:45:00",
             updated_at="2026-10-17 11:45:00"),
        # 旧中心同键已是终态（SUCCESS）：outbox 残留不能被当成「中心没落库」重新注册
        dict(task_type=TYPES["lock"], biz_id="😀:7", status="PENDING",
             action_time="2026-10-17 12:50:00", created_at="2026-10-17 11:45:00",
             updated_at="2026-10-17 11:45:00"),
        # SENT 不能被搬
        dict(task_type=TYPES["pay"], biz_id="7007", status="SENT",
             action_time="2026-10-17 15:00:00", created_at="2026-10-17 11:45:00",
             updated_at="2026-10-17 11:45:00"),
    ])

    print("== 预检：旧 job 启用 ==")
    set_job("timeoutTaskScanJob", 1)
    code, _ = run_tool("forward", "--allow-existing-target", "--dry-run")
    expect(code == 2, "旧 job=1 时 forward 退出码 2")

    print("== 预检：新 job 启用 ==")
    set_job("timeoutTaskScanJob", 0)
    set_job("timeoutTaskScan", 1)
    code, _ = run_tool("forward", "--allow-existing-target", "--dry-run")
    expect(code == 2, "新 job=1 时 forward 退出码 2")

    print("== forward dry-run ==")
    set_job("timeoutTaskScan", 0)
    code, report = run_tool("forward", "--allow-existing-target", "--dry-run")
    expect(code == 0, "dry-run 退出码 0")
    expect(report["counts"]["source_rows"] == 5, "源 5 行进计划（3 个非终态 + FAILED）")
    expect(report["counts"]["outbox_rows"] == 3, "outbox 只读到 3 条 PENDING")
    expect(report["counts"]["planned_inserts"] == 5, "dry-run 计划插入 5 行")
    expect(report["counts"]["skipped"] == 3,
           "dry-run 跳过 3 行（目标已有 1001 + outbox 已有 2002 + 终态 😀:7）")
    expect(report["verification"]["ok"] is True, "dry-run 不做落库校验（ok 为 true）")

    print("== forward 正式 ==")
    code, report = run_tool("forward", "--allow-existing-target")
    expect(code == 0, "forward 退出码 0")
    expect(report["verification"]["ok"] is True, "forward 校验通过")
    expect(report["counts"]["planned_inserts"] == 5, "实际插入 5 行")
    expect(report["counts"]["target_rows_after"] == 7, "目标 7 行（5 插入 + 2 原有）")
    for key in ("1001", "2002", "3003:1", "4004:2", "订单-测试", "5005", "8008"):
        found = query(
            "SELECT COUNT(*) FROM ("
            + " UNION ALL ".join(
                f"SELECT biz_id FROM {q(DST, table(s))}" for s in range(16))
            + f") t WHERE biz_id = {literal(key)};")
        expect(found[0][0] == "1", f"目标库恰有 1 行 {key}")
    slot_1001 = migrate.new_slot("1001")
    expect(value(f"SELECT task_type FROM {q(DST, table(slot_1001))} "
                 f"WHERE biz_id = {literal('1001')};") == TYPES["pay"], "1001 在目标新槽位")
    # 旧中心同键已是 SUCCESS 的 outbox 残留不能复活
    slot_emoji = migrate.new_slot("😀:7")
    expect(value(f"SELECT COUNT(*) FROM {q(DST, table(slot_emoji))} "
                 f"WHERE biz_id = {literal('😀:7')};") == "0",
           "终态键 😀:7 没有被 outbox 残留复活")

    print("== forward 重跑（幂等） ==")
    code, report = run_tool("forward", "--allow-existing-target")
    expect(code == 0, "重跑退出码 0")
    expect(report["counts"]["planned_inserts"] == 0, "重跑 0 插入")
    expect(report["counts"]["skipped"] == 8, "重跑 8 跳过")

    print("== reverse 预检：旧 job 启用 ==")
    set_job("timeoutTaskScanJob", 1)
    code, _ = run_tool("reverse", "--dry-run")
    expect(code == 2, "旧 job=1 时 reverse 退出码 2")
    set_job("timeoutTaskScanJob", 0)

    print("== reverse 预检：新 job 启用 ==")
    set_job("timeoutTaskScan", 1)
    code, _ = run_tool("reverse", "--dry-run")
    expect(code == 2, "新 job=1 时 reverse 退出码 2")
    set_job("timeoutTaskScan", 0)

    # 模拟切换窗口后的状态漂移：
    #   * 新中心已把 1001 结算成 SUCCESS，旧中心还是 WAITING -> reverse 取消旧行
    #   * 旧中心把 订单-测试 结算成 SUCCESS，新中心还是 WAITING -> reverse 复活
    slot_1001 = migrate.new_slot("1001")
    sql(f"UPDATE {q(DST, table(slot_1001))} SET status = 'SUCCESS' "
        f"WHERE biz_id = {literal('1001')};", DST)
    old_slot_order = migrate.old_slot(TYPES["pay"], "订单-测试")
    sql(f"UPDATE {q(SRC, table(old_slot_order))} SET status = 'SUCCESS' "
        f"WHERE biz_id = {literal('订单-测试')};", SRC)

    print("== reverse dry-run ==")
    code, report = run_tool("reverse", "--dry-run")
    expect(code == 0, "reverse dry-run 退出码 0")
    expect(report["counts"]["planned_inserts"] == 2, "reverse 计划插入 2 行（5005/8008）")
    expect(report["counts"]["planned_revives"] == 1, "reverse 计划复活 1 行（订单-测试）")
    expect(report["counts"]["planned_cancels"] == 1, "reverse 计划取消 1 行（1001）")

    print("== reverse 正式 ==")
    code, report = run_tool("reverse")
    expect(code == 0, "reverse 退出码 0")
    expect(report["verification"]["ok"] is True, "reverse 校验通过")

    expected_old = {
        "1001": "CANCELED",
        "2002": "RETRYING",
        "3003:1": "WAITING",
        "4004:2": "FAILED",
        "订单-测试": "WAITING",
        "5005": "WAITING",
        "8008": "WAITING",
    }
    for key, status in expected_old.items():
        task_type = {
            "1001": TYPES["pay"], "2002": TYPES["errand_pay"], "3003:1": TYPES["lock"],
            "4004:2": TYPES["review"], "订单-测试": TYPES["pay"],
            "5005": TYPES["pay"], "8008": TYPES["pay"],
        }[key]
        slot = migrate.old_slot(task_type, key)
        actual = value(f"SELECT status FROM {q(SRC, table(slot))} "
                       f"WHERE task_type = {literal(task_type)} AND biz_id = {literal(key)};")
        expect(actual == status, f"旧库 {key} status={status}（实际 {actual}）")

    print("== 目标行放错分表 -> forward 校验退出码 3 ==")
    key, slot = "8008", migrate.new_slot("8008")
    wrong = (slot + 1) % 16
    sql(f"INSERT INTO {q(DST, table(wrong))} "
        "(task_type, biz_id, action_time, next_fire_time, status, retry_count, created_at, updated_at) "
        f"SELECT task_type, biz_id, action_time, next_fire_time, status, retry_count, created_at, updated_at "
        f"FROM {q(DST, table(slot))} WHERE biz_id = {literal(key)}; "
        f"DELETE FROM {q(DST, table(slot))} WHERE biz_id = {literal(key)};", DST)
    code, report = run_tool("forward", "--allow-existing-target")
    expect(code == 3, "错表时 forward 退出码 3")
    expect(report["verification"]["ok"] is False, "报告 verification.ok=false")
    expect(any("槽位错误" in item for item in report["verification"]["errors"]),
           "报告指出槽位错误")

    # 复原，避免影响下一轮
    sql(f"INSERT INTO {q(DST, table(slot))} "
        "(task_type, biz_id, action_time, next_fire_time, status, retry_count, created_at, updated_at) "
        f"SELECT task_type, biz_id, action_time, next_fire_time, status, retry_count, created_at, updated_at "
        f"FROM {q(DST, table(wrong))} WHERE biz_id = {literal(key)}; "
        f"DELETE FROM {q(DST, table(wrong))} WHERE biz_id = {literal(key)};", DST)

    print("\nE2E 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
