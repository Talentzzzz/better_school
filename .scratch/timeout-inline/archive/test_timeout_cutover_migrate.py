#!/usr/bin/env python3
"""`timeout-cutover-migrate.py` 的离线单测（不需要 MySQL）。

运行：

    python backend/scripts/test_timeout_cutover_migrate.py -v

槽位夹具由 JDK 21 直接跑 `String.hashCode()` 得到（见票 07 的 Answer），
不是用 Python 反推的——这个文件的唯一价值就是钉住「Python 工具与 Java 运行时逐字节一致」。
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("timeout-cutover-migrate.py")
SPEC = importlib.util.spec_from_file_location("timeout_cutover_migrate", MODULE_PATH)
assert SPEC and SPEC.loader
migrate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = migrate
SPEC.loader.exec_module(migrate)


def row(
    task_type: str,
    biz_id: str,
    status: str = "WAITING",
    *,
    table: str = "timeout_task_00",
    action_time: str = "2026-10-17 12:00:00",
    next_fire_time: str | None = None,
    retry_count: int = 0,
    origin: str = "center",
) -> "migrate.TaskRow":
    return migrate.TaskRow(
        table=table,
        task_type=task_type,
        biz_id=biz_id,
        action_time=action_time,
        next_fire_time=next_fire_time or action_time,
        status=status,
        retry_count=retry_count,
        created_at="2026-10-17 11:45:00",
        updated_at="2026-10-17 11:45:00",
        origin=origin,
    )


class JavaHashTest(unittest.TestCase):
    """夹具：Java 21 `String.hashCode()` / `String.length()` 的实测输出。"""

    def test_ascii_hash(self) -> None:
        self.assertEqual(migrate.java_hash("1001"), 1507424)
        self.assertEqual(migrate.java_hash("3003:1"), 1505896535)
        self.assertEqual(migrate.java_hash("4004:2"), 1534526648)

    def test_cjk_hash(self) -> None:
        self.assertEqual(migrate.java_hash("订单-测试"), -711078908)

    def test_surrogate_pair_hash(self) -> None:
        # Java String.length() 数 UTF-16 code unit：emoji 是 2，不是 1。
        self.assertEqual(migrate.java_hash("😀:7"), 1703757792)
        self.assertEqual(migrate.java_length("😀:7"), 4)

    def test_cjk_length(self) -> None:
        self.assertEqual(migrate.java_length("订单-测试"), 5)

    def test_old_slot_fixtures(self) -> None:
        cases = [
            ("PRODUCT_ORDER_PAY_TIMEOUT", "1001", 2),
            ("ERRAND_PAY_TIMEOUT", "2002", 0),
            ("ERRAND_LOCK_TIMEOUT", "3003:1", 15),
            ("REVIEW_LEASE_TIMEOUT", "4004:2", 9),
            ("PRODUCT_ORDER_PAY_TIMEOUT", "订单-测试", 2),
            ("ERRAND_LOCK_TIMEOUT", "😀:7", 8),
        ]
        for task_type, biz_id, expected in cases:
            with self.subTest(task_type=task_type, biz_id=biz_id):
                self.assertEqual(migrate.old_slot(task_type, biz_id), expected)

    def test_new_slot_fixtures(self) -> None:
        cases = [
            ("1001", 0),
            ("2002", 0),
            ("3003:1", 7),
            ("4004:2", 8),
            ("订单-测试", 4),
            ("😀:7", 0),
        ]
        for biz_id, expected in cases:
            with self.subTest(biz_id=biz_id):
                self.assertEqual(migrate.new_slot(biz_id), expected)

    def test_physical_table_zero_pads(self) -> None:
        self.assertEqual(migrate.physical_table(0), "timeout_task_00")
        self.assertEqual(migrate.physical_table(15), "timeout_task_15")
        with self.assertRaises(migrate.MigrationError):
            migrate.physical_table(16)


class ForwardPlanTest(unittest.TestCase):

    def test_missing_key_is_inserted_into_new_slot(self) -> None:
        source = [row("ERRAND_LOCK_TIMEOUT", "3003:1", table="timeout_task_15")]
        plan = migrate.plan_forward(source, [], [])
        self.assertEqual(len(plan.inserts), 1)
        self.assertEqual(plan.inserts[0].target_table, "timeout_task_07")
        self.assertFalse(plan.skips)

    def test_existing_target_wins(self) -> None:
        source = [row("ERRAND_LOCK_TIMEOUT", "3003:1", table="timeout_task_15")]
        target = [row("ERRAND_LOCK_TIMEOUT", "3003:1", status="RETRYING",
                      table="timeout_task_07", retry_count=3)]
        plan = migrate.plan_forward(source, [], target)
        self.assertFalse(plan.inserts)
        self.assertEqual(len(plan.skips), 1)
        self.assertEqual(plan.skips[0].target_table, "timeout_task_07")
        self.assertIn("保留目标行", plan.skips[0].reason)

    def test_outbox_row_is_migrated_when_center_lost_it(self) -> None:
        outbox = [row("PRODUCT_ORDER_PAY_TIMEOUT", "1001", origin="outbox",
                      table="timeout_register_outbox")]
        plan = migrate.plan_forward([], outbox, [])
        self.assertEqual(len(plan.inserts), 1)
        self.assertEqual(plan.inserts[0].row.origin, "outbox")
        self.assertEqual(plan.inserts[0].target_table, "timeout_task_00")

    def test_outbox_row_skipped_when_center_already_has_key(self) -> None:
        source = [row("PRODUCT_ORDER_PAY_TIMEOUT", "1001")]
        outbox = [row("PRODUCT_ORDER_PAY_TIMEOUT", "1001", origin="outbox",
                      table="timeout_register_outbox")]
        plan = migrate.plan_forward(source, outbox, [])
        self.assertEqual(len(plan.inserts), 1)
        self.assertEqual(plan.inserts[0].row.origin, "center")
        self.assertEqual(len(plan.skips), 1)
        self.assertIn("outbox", plan.skips[0].reason)

    def test_outbox_row_skipped_when_center_terminal_and_outbox_is_stale(self) -> None:
        outbox = [row("PRODUCT_ORDER_PAY_TIMEOUT", "1001", origin="outbox",
                      table="timeout_register_outbox", action_time="2026-10-17 12:00:00")]
        old = row("PRODUCT_ORDER_PAY_TIMEOUT", "1001", status="SUCCESS",
                  table="timeout_task_00", action_time="2026-10-17 13:00:00")
        plan = migrate.plan_forward(
            [], outbox, [],
            source_by_key={("PRODUCT_ORDER_PAY_TIMEOUT", "1001"): old},
        )
        self.assertFalse(plan.inserts)
        self.assertEqual(len(plan.skips), 1)
        self.assertIn("SUCCESS", plan.skips[0].reason)
        self.assertIn("残留", plan.skips[0].reason)

    def test_outbox_row_migrated_when_center_terminal_but_outbox_is_newer(self) -> None:
        # outbox uk 会复位 PENDING：终态旧行 + 更晚 action_time = 新一轮注册，不能丢。
        outbox = [row("PRODUCT_ORDER_PAY_TIMEOUT", "1001", origin="outbox",
                      table="timeout_register_outbox", action_time="2026-10-17 14:00:00")]
        old = row("PRODUCT_ORDER_PAY_TIMEOUT", "1001", status="SUCCESS",
                  table="timeout_task_00", action_time="2026-10-17 13:00:00")
        plan = migrate.plan_forward(
            [], outbox, [],
            source_by_key={("PRODUCT_ORDER_PAY_TIMEOUT", "1001"): old},
        )
        self.assertEqual(len(plan.inserts), 1)
        self.assertEqual(plan.inserts[0].row.origin, "outbox")
        self.assertFalse(plan.skips)

    def test_duplicate_source_key_is_fatal(self) -> None:
        source = [
            row("ERRAND_LOCK_TIMEOUT", "3003:1", table="timeout_task_15"),
            row("ERRAND_LOCK_TIMEOUT", "3003:1", table="timeout_task_08"),
        ]
        with self.assertRaises(migrate.MigrationError):
            migrate.plan_forward(source, [], [])

    def test_failed_dead_letter_is_carried_over(self) -> None:
        source = [row("REVIEW_LEASE_TIMEOUT", "4004:2", status="FAILED", retry_count=16)]
        plan = migrate.plan_forward(source, [], [])
        self.assertEqual(plan.inserts[0].row.status, "FAILED")
        self.assertEqual(plan.inserts[0].row.retry_count, 16)

    def test_verify_forward_does_not_require_terminal_outbox_row(self) -> None:
        outbox = [row("ERRAND_LOCK_TIMEOUT", "3003:1", origin="outbox",
                      table="timeout_register_outbox", action_time="2026-10-17 12:00:00")]
        old = row("ERRAND_LOCK_TIMEOUT", "3003:1", status="SUCCESS",
                  table="timeout_task_15", action_time="2026-10-17 13:00:00")
        plan = migrate.plan_forward(
            [], outbox, [],
            source_by_key={("ERRAND_LOCK_TIMEOUT", "3003:1"): old},
        )
        self.assertEqual(migrate.verify_forward(migrate.Config(), plan, []), [])

    def test_verify_forward_reports_missing_source_row(self) -> None:
        source = [row("ERRAND_LOCK_TIMEOUT", "3003:1", table="timeout_task_15")]
        plan = migrate.plan_forward(source, [], [])
        errors = migrate.verify_forward(migrate.Config(), plan, [])
        self.assertTrue(any("未出现在目标库" in error for error in errors))


class ReversePlanTest(unittest.TestCase):

    def test_new_target_row_is_inserted_into_old_slot(self) -> None:
        target = [row("ERRAND_LOCK_TIMEOUT", "3003:1", table="timeout_task_07")]
        plan = migrate.plan_reverse(target, [])
        self.assertEqual(len(plan.inserts), 1)
        self.assertEqual(plan.inserts[0].target_table, "timeout_task_15")

    def test_old_active_row_is_not_duplicated(self) -> None:
        target = [row("ERRAND_LOCK_TIMEOUT", "3003:1", table="timeout_task_07")]
        old = [row("ERRAND_LOCK_TIMEOUT", "3003:1", table="timeout_task_15", retry_count=2)]
        plan = migrate.plan_reverse(target, old)
        self.assertFalse(plan.inserts)
        self.assertEqual(len(plan.skips), 1)
        self.assertIn("旧中心已有在途行", plan.skips[0].reason)

    def test_old_terminal_row_is_revived(self) -> None:
        target = [row("ERRAND_LOCK_TIMEOUT", "3003:1", status="RETRYING",
                      table="timeout_task_07", retry_count=3)]
        old = [row("ERRAND_LOCK_TIMEOUT", "3003:1", status="SUCCESS",
                   table="timeout_task_15")]
        plan = migrate.plan_reverse(target, old)
        self.assertEqual(len(plan.revives), 1)
        self.assertEqual(plan.revives[0].target_table, "timeout_task_15")

    def test_target_success_cancels_stale_old_active_row(self) -> None:
        target = [row("ERRAND_LOCK_TIMEOUT", "3003:1", status="SUCCESS",
                      table="timeout_task_07")]
        old = [row("ERRAND_LOCK_TIMEOUT", "3003:1", status="WAITING",
                   table="timeout_task_15")]
        plan = migrate.plan_reverse(target, old)
        self.assertEqual(len(plan.cancels), 1)
        self.assertFalse(plan.inserts)

    def test_stale_cancel_can_be_disabled(self) -> None:
        target = [row("ERRAND_LOCK_TIMEOUT", "3003:1", status="SUCCESS",
                      table="timeout_task_07")]
        old = [row("ERRAND_LOCK_TIMEOUT", "3003:1", status="WAITING",
                   table="timeout_task_15")]
        plan = migrate.plan_reverse(target, old, cancel_stale_old=False)
        self.assertFalse(plan.cancels)
        self.assertEqual(len(plan.skips), 1)

    def test_old_active_row_is_kept_when_target_is_failed(self) -> None:
        target = [row("REVIEW_LEASE_TIMEOUT", "4004:2", status="FAILED",
                      table="timeout_task_08", retry_count=16)]
        old = [row("REVIEW_LEASE_TIMEOUT", "4004:2", status="RETRYING",
                   table="timeout_task_09", retry_count=5)]
        plan = migrate.plan_reverse(target, old)
        self.assertFalse(plan.inserts)
        self.assertFalse(plan.revives)
        self.assertEqual(len(plan.skips), 1)
        self.assertIn("继续重试", plan.skips[0].reason)

    def test_failed_target_without_old_row_is_inserted(self) -> None:
        target = [row("REVIEW_LEASE_TIMEOUT", "4004:2", status="FAILED",
                      table="timeout_task_08", retry_count=16)]
        plan = migrate.plan_reverse(target, [])
        self.assertEqual(len(plan.inserts), 1)
        self.assertEqual(plan.inserts[0].target_table, "timeout_task_09")

    def test_failed_target_with_old_terminal_keeps_old_terminal(self) -> None:
        # 旧中心已结算（SUCCESS/CANCELED）时不拿新死信覆盖：业务事实已发生。
        target = [row("REVIEW_LEASE_TIMEOUT", "4004:2", status="FAILED",
                      table="timeout_task_08", retry_count=16)]
        old = [row("REVIEW_LEASE_TIMEOUT", "4004:2", status="SUCCESS",
                   table="timeout_task_09")]
        plan = migrate.plan_reverse(target, old)
        self.assertFalse(plan.inserts)
        self.assertFalse(plan.revives)
        self.assertEqual(len(plan.skips), 1)
        self.assertIn("保留旧终态", plan.skips[0].reason)


class JobGuardTest(unittest.TestCase):
    """扫描 job 前置守卫：读不到 xxl 默认拒绝，两个 job 启用都阻塞。"""

    def test_unreadable_xxl_requires_force(self) -> None:
        with self.assertRaises(migrate.MigrationError):
            migrate.check_job_statuses(migrate.Config(), None, "forward")

    def test_unreadable_xxl_allowed_with_force(self) -> None:
        warnings = migrate.check_job_statuses(migrate.Config(force=True), None, "forward")
        self.assertTrue(any("--force" in warning for warning in warnings))

    def test_old_job_enabled_blocks_forward(self) -> None:
        with self.assertRaises(migrate.MigrationError):
            migrate.check_job_statuses(
                migrate.Config(), {"timeoutTaskScanJob": 1, "timeoutTaskScan": 0}, "forward"
            )

    def test_old_job_enabled_blocks_reverse(self) -> None:
        with self.assertRaises(migrate.MigrationError):
            migrate.check_job_statuses(
                migrate.Config(), {"timeoutTaskScanJob": 1, "timeoutTaskScan": 0}, "reverse"
            )

    def test_new_job_enabled_blocks_forward(self) -> None:
        with self.assertRaises(migrate.MigrationError):
            migrate.check_job_statuses(
                migrate.Config(), {"timeoutTaskScanJob": 0, "timeoutTaskScan": 1}, "forward"
            )

    def test_missing_new_job_warns_seed(self) -> None:
        warnings = migrate.check_job_statuses(
            migrate.Config(), {"timeoutTaskScanJob": 0}, "forward"
        )
        self.assertTrue(any("xxl-job-tasks.sql" in warning for warning in warnings))

    def test_no_jobs_warns(self) -> None:
        warnings = migrate.check_job_statuses(migrate.Config(), {}, "forward")
        self.assertTrue(any("没有" in warning for warning in warnings))


class SqlGenerationTest(unittest.TestCase):
    def test_sql_text_uses_hex_literal(self) -> None:
        self.assertEqual(
            migrate.sql_text("订单-测试"),
            "CONVERT(X'"
            + "订单-测试".encode("utf-8").hex()
            + "' USING utf8mb4)",
        )

    def test_forward_insert_has_no_callback_group(self) -> None:
        cfg = migrate.Config()
        item = migrate.PlannedInsert("timeout_task_00", row("PRODUCT_ORDER_PAY_TIMEOUT", "1001"))
        sql = migrate.insert_forward_sql(cfg, item)
        self.assertNotIn("callback_group", sql)
        self.assertIn("`better_school`.`timeout_task_00`", sql)

    def test_reverse_insert_has_callback_group(self) -> None:
        cfg = migrate.Config(callback_group="better-school")
        item = migrate.PlannedInsert("timeout_task_02", row("PRODUCT_ORDER_PAY_TIMEOUT", "1001"))
        sql = migrate.insert_reverse_sql(cfg, item)
        self.assertIn("callback_group", sql)
        self.assertIn(migrate.sql_text("better-school"), sql)

    def test_outbox_select_uses_real_column_names(self) -> None:
        # 回归：outbox 列名是 type/state/create_time/update_time（票 03 删表前的 DDL），
        # 不是分表的 task_type/status/created_at/updated_at。
        sql = migrate.select_outbox_sql("better_school")
        self.assertIn("HEX(type)", sql)
        self.assertIn("state = 'PENDING'", sql)
        self.assertIn("create_time", sql)
        self.assertIn("update_time", sql)
        self.assertNotIn("task_type", sql)
        self.assertNotIn("status =", sql)
        self.assertNotIn("created_at", sql)
        self.assertNotIn("updated_at", sql)

    def test_revive_reverse_sql_guards_terminal_status(self) -> None:
        cfg = migrate.Config()
        item = migrate.PlannedInsert(
            "timeout_task_15", row("ERRAND_LOCK_TIMEOUT", "3003:1", status="RETRYING")
        )
        sql = migrate.revive_reverse_sql(cfg, item)
        self.assertIn("`bs_timeout`.`timeout_task_15`", sql)
        self.assertIn("AND status IN ('SUCCESS', 'FAILED', 'CANCELED')", sql)
        self.assertIn(migrate.sql_text("3003:1"), sql)

    def test_cancel_reverse_sql_only_hits_active_rows(self) -> None:
        cfg = migrate.Config()
        item = migrate.PlannedInsert(
            "timeout_task_15", row("ERRAND_LOCK_TIMEOUT", "3003:1", status="SUCCESS")
        )
        sql = migrate.cancel_reverse_sql(cfg, item)
        self.assertIn("status = 'CANCELED'", sql)
        self.assertIn("AND status IN ('WAITING', 'RETRYING')", sql)

    def test_forward_script_is_one_transaction(self) -> None:
        cfg = migrate.Config()
        plan = migrate.ForwardPlan(
            inserts=[
                migrate.PlannedInsert(
                    "timeout_task_00", row("PRODUCT_ORDER_PAY_TIMEOUT", "1001")
                )
            ]
        )
        sql = migrate.forward_script(cfg, plan)
        self.assertTrue(sql.startswith("SET NAMES utf8mb4;\nSTART TRANSACTION;"))
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))


if __name__ == "__main__":
    unittest.main()
