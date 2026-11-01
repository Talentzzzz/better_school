#!/usr/bin/env python3
"""
积分消费明细分组行数分布 —— 票 01 的实测探针（不属于产品代码，只产读数）。

背景：本机没有生产数据（bs_marketing / bs_marketing_test 的 points_detail 均为 0 行），
所以按票面要求「按（用户，原因，业务 ID）分组统计行数分布」只能在**按仓内已配置参数重放的
入账/兑换流水**上做。重放规则逐条对齐实现：

  * 兑换价 100 分/次          —— marketing-types raffle/Constants.DRAW_COUNT_EXCHANGE_PRICE
  * 积分有效期 365 天         —— points_validity_config 种子
  * 签到返利 20 分/天         —— action_rebate_config 种子 (sign → POINTS → 20)
  * 中奖积分面额 5 / 20       —— 文档与用例里出现过的 virtual_inventory_quota.value
  * FIFO 扣减                 —— PointsRepository.deduct：remain>0 AND expire_at>NOW()
                                 ORDER BY expire_at, id，逐桶 take = min(remain, remaining)
  * 余量不够整笔拒绝、不半扣  —— 同上

产出：把重放出的 points_bucket / points_detail 行灌进 scratch 库（points_probe_a/b/c），
再用真 SQL 做 GROUP BY，分布数字一律来自 SQL 查询结果，不是脚本自报。
"""
import os
import random
import subprocess
import sys
from datetime import datetime, timedelta

MYSQL = ["docker", "exec", "-i", "bs-mysql", "mysql", "-uroot", "-pbs123456",
         "--default-character-set=utf8mb4"]

DDL = """
CREATE TABLE IF NOT EXISTS points_bucket (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    bucket_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    amount      INT         NOT NULL,
    remain      INT         NOT NULL,
    expire_at   DATETIME    NOT NULL,
    reason_type VARCHAR(30) NOT NULL,
    ref_id      BIGINT      NOT NULL,
    create_time DATETIME    NOT NULL,
    update_time DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_id (bucket_id),
    UNIQUE KEY uk_user_reason_ref (user_id, reason_type, ref_id),
    KEY idx_user_expire (user_id, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS points_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    bucket_id   BIGINT      NOT NULL,
    amount      INT         NOT NULL,
    reason_type VARCHAR(30) NOT NULL,
    ref_id      BIGINT      NOT NULL,
    create_time DATETIME    NOT NULL,
    update_time DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_reason_ref (bucket_id, reason_type, ref_id),
    KEY idx_user_ref (user_id, reason_type, ref_id),
    KEY idx_bucket (bucket_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
"""

EXCHANGE_PRICE = 100
VALID_DAYS = 365
SIGN_IN_POINTS = 20
HORIZON_DAYS = 365
USERS = 300


class Ledger:
    """一个用户的积分桶账本 + FIFO 扣减（照抄 PointsRepository.deduct 的循环）"""

    def __init__(self):
        self.buckets = []          # [bucket_id, amount, remain, expire_at, reason_type, ref_id, create_time]
        self.details = []          # [bucket_id, amount, reason_type, ref_id, create_time]
        self.next_bucket_id = 0

    def credit(self, amount, reason_type, ref_id, day):
        self.next_bucket_id += 1
        expire_at = day + timedelta(days=VALID_DAYS)
        self.buckets.append([self.next_bucket_id, amount, amount, expire_at, reason_type, ref_id, day])

    def available(self, now):
        return [b for b in self.buckets if b[2] > 0 and b[3] > now]

    def balance(self, now):
        return sum(b[2] for b in self.available(now))

    def deduct(self, amount, reason_type, ref_id, now):
        """返回本次落了几行消费明细；不够整笔返回 None（一行都不动）"""
        buckets = sorted(self.available(now), key=lambda b: (b[3], b[0]))
        if sum(b[2] for b in buckets) < amount:
            return None
        remaining = amount
        rows = 0
        for b in buckets:
            if remaining == 0:
                break
            take = min(b[2], remaining)
            b[2] -= take
            self.details.append([b[0], take, reason_type, ref_id, now])
            remaining -= take
            rows += 1
        return rows


def simulate(persona, award_amounts, rng):
    """重放一个用户的一年：每天可能签到、想抽奖；想抽但没次数且够 100 分就兑换。"""
    ledger = Ledger()
    redeem_rows = []
    record_id = 0
    redeem_id = 0
    draw_count = 0
    start = datetime(2025, 10, 18)
    sign_rate, draws_per_week = persona

    for d in range(HORIZON_DAYS):
        day = start + timedelta(days=d)
        if rng.random() < sign_rate:
            ledger.credit(SIGN_IN_POINTS, "REBATE", 1_000_000 + d * 10 + rng.randint(0, 9), day)

        # 想抽几天次（按周频率折算成当天期望次数）
        wants = 1 if rng.random() < draws_per_week / 7.0 else 0
        for _ in range(wants):
            if draw_count == 0:
                redeem_id += 1
                rows = ledger.deduct(EXCHANGE_PRICE, "REDEEM_DRAW_COUNT", redeem_id, day)
                if rows is None:
                    continue          # 积分不够，这一抽作罢（次数 30 天有效期，与积分桶无关）
                redeem_rows.append(rows)
                draw_count += 1
            draw_count -= 1
            record_id += 1
            if award_amounts and rng.random() < 0.35:   # 中奖才发积分（谢谢参与/实物档不加积分桶）
                ledger.credit(rng.choice(award_amounts), "LOTTERY_WIN", record_id, day)
    return ledger, redeem_rows


SCENARIOS = {
    # A：仓内文档口径 —— 签到 20/天 + 中奖积分 5 / 20
    "a": {"awards": [5, 20]},
    # B：压力口径 —— 中奖积分为 100 / 200 的大额档（1 行占比的最有利情形）
    "b": {"awards": [100, 200]},
    # C：纯签到，不抽奖（最干净的「只有 20 分桶」口径）
    "c": {"awards": []},
}

PERSONAS = [
    ((0.85, 3.0), 0.15),   # 重度：一周签到 6 天、一周抽 3 次
    ((0.45, 1.0), 0.35),   # 中度：一周 3 天、一周抽 1 次
    ((0.15, 0.3), 0.50),   # 轻度：一周 1 天、两周抽 1 次以内
]


def build_rows(scenario_key):
    rng = random.Random(20261018)
    awards = SCENARIOS[scenario_key]["awards"]
    bucket_rows, detail_rows, rows_per_redeem = [], [], []
    for u in range(USERS):
        persona = rng.choices([p for p, _ in PERSONAS], weights=[w for _, w in PERSONAS])[0]
        ledger, redeem_rows = simulate(persona, awards, rng)
        for b in ledger.buckets:
            bucket_rows.append([100_000 + u] + [b[0] + u * 10_000_000] + b[1:])
        for det in ledger.details:
            detail_rows.append([100_000 + u, det[0] + u * 10_000_000] + det[1:])
        rows_per_redeem.extend(redeem_rows)
    return bucket_rows, detail_rows, rows_per_redeem


def sql_ts(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def dump_and_load(db, bucket_rows, detail_rows, path):
    # bucket_rows 元素布局：[user_id, bucket_id, amount, remain, expire_at, reason_type, ref_id, create_time]
    # detail_rows 元素布局：[user_id, bucket_id, amount, reason_type, ref_id, create_time]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for i in range(0, len(bucket_rows), 500):
            values = ",".join(
                "(%d,%d,%d,%d,'%s','%s',%d,'%s','%s')"
                % (bucket_id, user_id, amount, remain, sql_ts(expire_at), reason_type, ref_id,
                   sql_ts(created), sql_ts(created))
                for user_id, bucket_id, amount, remain, expire_at, reason_type, ref_id, created
                in bucket_rows[i:i + 500])
            f.write("INSERT IGNORE INTO points_bucket (bucket_id,user_id,amount,remain,expire_at,reason_type,"
                    "ref_id,create_time,update_time) VALUES %s;\n" % values)
        for i in range(0, len(detail_rows), 500):
            values = ",".join(
                "(%d,%d,%d,'%s',%d,'%s','%s')"
                % (user_id, bucket_id, amount, reason_type, ref_id, sql_ts(created), sql_ts(created))
                for user_id, bucket_id, amount, reason_type, ref_id, created in detail_rows[i:i + 500])
            f.write("INSERT IGNORE INTO points_detail (user_id,bucket_id,amount,reason_type,ref_id,"
                    "create_time,update_time) VALUES %s;\n" % values)

    setup = "DROP DATABASE IF EXISTS %s; CREATE DATABASE %s CHARACTER SET utf8mb4;\n" % (db, db)
    run_mysql(setup.encode())
    run_mysql((DDL + "\n").encode(), extra_args=[db])
    with open(path, "rb") as body:
        run_mysql(None, body, [db])


def run_mysql(payload, stdin_file=None, extra_args=()):
    res = subprocess.run(MYSQL + list(extra_args), input=payload, stdin=stdin_file,
                         capture_output=True)
    if res.returncode != 0:
        raise SystemExit("mysql 失败：%s" % res.stderr.decode(errors="replace"))


DIST_SQL = """
SELECT CASE WHEN g.rows_per_group = 1 THEN '1 行'
            WHEN g.rows_per_group BETWEEN 2 AND 3 THEN '2-3 行'
            WHEN g.rows_per_group BETWEEN 4 AND 10 THEN '4-10 行'
            ELSE '10 行以上' END AS bucket_label,
       COUNT(*) AS grp_cnt,
       ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM (SELECT 1 FROM points_detail
             GROUP BY user_id, reason_type, ref_id) t), 2) AS pct
FROM (SELECT user_id, reason_type, ref_id, COUNT(*) AS rows_per_group
      FROM points_detail GROUP BY user_id, reason_type, ref_id) g
GROUP BY bucket_label
ORDER BY FIELD(bucket_label, '1 行', '2-3 行', '4-10 行', '10 行以上');
"""

SUMMARY_SQL = """
SELECT
  (SELECT COUNT(*) FROM points_bucket) AS buckets,
  (SELECT ROUND(AVG(c), 1) FROM (SELECT COUNT(*) c FROM points_bucket GROUP BY user_id) t) AS avg_buckets_per_user,
  (SELECT MAX(c) FROM (SELECT COUNT(*) c FROM points_bucket GROUP BY user_id) t) AS max_buckets_per_user,
  (SELECT COUNT(*) FROM (SELECT 1 FROM points_detail GROUP BY user_id, reason_type, ref_id) t) AS redeem_groups,
  (SELECT ROUND(AVG(c), 2) FROM (SELECT COUNT(*) c FROM points_detail GROUP BY user_id, reason_type, ref_id) t)
      AS avg_rows_per_redeem,
  (SELECT MAX(c) FROM (SELECT COUNT(*) c FROM points_detail GROUP BY user_id, reason_type, ref_id) t)
      AS max_rows_per_redeem;
"""


def run_sql(db, sql):
    res = subprocess.run(MYSQL + ["-N", "-B", db, "-e", sql], capture_output=True, text=True)
    if res.returncode != 0:
        raise SystemExit(res.stderr)
    return res.stdout.strip()


def sweep():
    """灵敏度：固定兑换价 100，改入账面额，看「每次兑换跨几张券」——行数 ≈ 兑换价 / 桶面额。
    仅在内存里跑（同一重放引擎），不落库。"""
    print("%-10s %-8s %-10s %-10s %s" % ("桶面额", "兑换组数", "平均行数", "最大行数", "分布"))
    for size in (5, 10, 20, 50, 100):
        rng = random.Random(20261018)
        rows_all = []
        for _ in range(USERS):
            persona = rng.choices([p for p, _ in PERSONAS], weights=[w for _, w in PERSONAS])[0]
            ledger = Ledger()
            sign_rate, draws_per_week = persona
            start = datetime(2025, 10, 18)
            draw_count = 0
            redeem_id = 0
            for d in range(HORIZON_DAYS):
                day = start + timedelta(days=d)
                if rng.random() < sign_rate:
                    ledger.credit(size, "REBATE", 1_000_000 + d * 10 + rng.randint(0, 9), day)
                if rng.random() < draws_per_week / 7.0:
                    if draw_count == 0:
                        redeem_id += 1
                        rows = ledger.deduct(EXCHANGE_PRICE, "REDEEM_DRAW_COUNT", redeem_id, day)
                        if rows is None:
                            continue
                        rows_all.append(rows)
                        draw_count += 1
                    draw_count -= 1
        buckets = {"1 行": 0, "2-3 行": 0, "4-10 行": 0, "10 行以上": 0}
        for r in rows_all:
            key = "1 行" if r == 1 else "2-3 行" if r <= 3 else "4-10 行" if r <= 10 else "10 行以上"
            buckets[key] += 1
        total = len(rows_all)
        dist = ", ".join("%s %.1f%%" % (k, v * 100.0 / total) for k, v in buckets.items() if v)
        print("%-10d %-8d %-10.2f %-10d %s"
              % (size, total, sum(rows_all) / total, max(rows_all), dist))


def main():
    out_dir = os.path.dirname(os.path.abspath(__file__))
    print("# 票 01 读数：积分消费明细按 (user_id, reason_type, ref_id) 分组的行数分布")
    print("# 口径：本机无生产数据（bs_marketing / bs_marketing_test 的 points_detail 均 0 行），")
    print("#       按仓内已配置参数重放一年流水后灌进 scratch 库，分布数字来自 SQL 查询。")
    print("# 参数：兑换价 100 分/次、积分有效期 365 天、签到返利 20 分/天、FIFO 逐桶扣减（照抄 PointsRepository）")
    print("# summary 列：桶行数 | 人均桶数 | 单人最多桶数 | 兑换组数 | 平均行数/组 | 最大行数/组")
    print("# 场景：a=签到20+中奖5/20（仓内文档口径） b=中奖100/200（大额面额） c=纯签到、不抽奖")
    for key in ("a", "b", "c"):
        db = "points_probe_" + key
        buckets, details, rows_per_redeem = build_rows(key)
        dump_and_load(db, buckets, details, os.path.join(out_dir, "probe_%s.sql" % key))
        print("=" * 70)
        print("scenario %s  awards=%s" % (key, SCENARIOS[key]["awards"]))
        print("summary:", run_sql(db, SUMMARY_SQL).replace("\t", " | "))
        print("distribution:")
        print(run_sql(db, DIST_SQL).replace("\t", " | "))
    print("=" * 70)
    print("sweep（兑换价恒 100）:")
    sweep()


if __name__ == "__main__":
    main()
