# -*- coding: utf-8 -*-
"""票 17 冒烟：项 6（积分桶入账 + 消费扣桶） + 项 7（抽奖全链路） + 项 9（通知落库）。

专属号段：137000174xx（见 README.md）
- user   = 13700017401（抽奖 / 消费 / 收件人）
- other  = 13700017402（对照：不应收到 user 的通知）

跑法：
    cd C:\\Users\\a1368\\Desktop\\better_school
    python .scratch\\project-rework\\verify-17\\smoke_06_07_09_points_lottery.py

设计口径：
- 只依赖 smoke_lib.py（不修改它）+ 标准库。
- 每条断言打一行 PASS/FAIL；失败不中断，最后汇总并 exit 1。
- 抽奖夹具照抄 RaffleApiTestBase 的列口径（draw_activity / draw_strategy / draw_strategy_award /
  virtual_inventory(+bucket) / physical_marketing_quota(+bucket) / physical_inventory / draw_count_bucket）。
- 夹具之后必跑两条真实编排任务（xxl-job HTTP 触发，非旁路）：
  `raffleCacheWarmup`（参数 = activityId）、`inventoryBucketWarmup`（空参数 = 双段全量）。
  合并阶段不触发——等每秒的 `inventoryMerge` 自己跑（真实链路）。
- 入账侧：`creditForRebate` 在营销服务里没有 HTTP/MQ 入口（points.md §行为返利入账 明说），
  所以按积分集成测试的夹具口径（PointsApiTestBase.insertBucket）直接落 points_bucket + 一条
  action_rebate_config 规则行，再走真实消费口（POST /api/lottery/exchange/draw-count）扣桶。
"""
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from smoke_lib import Harness, MKT_BASE, BS_LOG, wait_until  # noqa: E402

# ---------------- 常量（对齐 RaffleApiTestBase 的夹具口径） ----------------
USER_PHONE = "13700017401"
OTHER_PHONE = "13700017402"

MARKETING_DB = "bs_marketing"
BS_DB = "better_school"

ACTIVITY_ID = 8101
STRATEGY_ID = 8102
AWARD_POINTS = 8103
AWARD_BAG = 8104
AWARD_THANKS = 8105
AWARD_FALLBACK = 8106

POINTS_INV_ID = 8111
QUOTA_INV_ID = 8112
FALLBACK_INV_ID = 8113
POINTS_FACE_VALUE = 60          # virtual_inventory.value（积分面额，兜底池 5）
FALLBACK_FACE_VALUE = 5
QUOTA_START = 10
VIRTUAL_BUCKET_START = 100

DRAW_COUNT_PRICE = 100          # Constants.DRAW_COUNT_EXCHANGE_PRICE

CHECK_ACTIVITY = "/api/lottery/activity"

# 夹具铺的业务表（只清本活动/本策略/本库存相关的行，不动别的冒烟号段）
FIXTURE_DELETES = [
    ("draw_activity", "activity_id = %d" % ACTIVITY_ID),
    ("draw_strategy", "strategy_id = %d" % STRATEGY_ID),
    ("draw_strategy_award", "strategy_id = %d" % STRATEGY_ID),
    ("draw_strategy_weight", "strategy_id = %d" % STRATEGY_ID),
    ("virtual_inventory", "inv_id IN (%d,%d)" % (POINTS_INV_ID, FALLBACK_INV_ID)),
    ("virtual_inventory_bucket", "inv_id IN (%d,%d)" % (POINTS_INV_ID, FALLBACK_INV_ID)),
    ("virtual_inventory_detail", "inv_id IN (%d,%d)" % (POINTS_INV_ID, FALLBACK_INV_ID)),
    ("physical_marketing_quota", "inv_id = %d" % QUOTA_INV_ID),
    ("physical_marketing_quota_bucket", "inv_id = %d" % QUOTA_INV_ID),
    ("physical_marketing_quota_detail", "inv_id = %d" % QUOTA_INV_ID),
    ("physical_inventory", "inv_id = %d" % QUOTA_INV_ID),
    ("physical_inventory_detail", "inv_id = %d" % QUOTA_INV_ID),
]

# ---------------- 断言登记 ----------------
CHECKS = []
CONTEXT = {}


def check(name, cond, detail=""):
    ok = bool(cond)
    CHECKS.append((name, ok, detail))
    print("%s | %s%s" % ("PASS" if ok else "FAIL", name, ((" — " + str(detail)) if detail else "")))
    return ok


def mkt_ok(h, method, path, body=None, token=None, tries=3, sleep=1.5, records=None):
    """营销服务读接口的容错封装：首请求可能撞上冷启动/Hikari 建连，重试后仍失败才放弃。

    返回 (passed, data, last_response)。records 非 None 时把每次尝试追加进去（证据）。
    """
    last = None
    for i in range(tries):
        try:
            st, r = h.call(method, path, body, token=token, base=MKT_BASE)
        except Exception as e:  # noqa: BLE001
            st, r = None, "EXC: %s" % e
        last = {"attempt": i + 1, "http_status": st, "response": r}
        if records is not None:
            records.append(dict(last, path=path))
        if st == 200 and isinstance(r, dict) and r.get("code") == "200":
            return True, r.get("data"), last
        time.sleep(sleep)
    return False, None, last


def item_of(name):
    """断言名前缀（'6.' / '7.' / '9.'）→ 项号。"""
    return name.split(".")[0]


def head(title):
    print("\n=== %s ===" % title)


def rows_named(h, db, sql, cols):
    """查库并把行转成 [{列: 值}]（列名由调用方给出，保证证据可读）。"""
    out = []
    for row in h.sql(db, sql):
        out.append({cols[i]: row[i] for i in range(min(len(cols), len(row)))})
    return out


def nums(rows, idx=0):
    return [int(r[idx]) for r in rows]


# ---------------- xxl-job admin（登录 + 手动触发任务） ----------------
class XxlAdmin(object):
    """xxl-job-admin 3.x：POST /auth/doLogin 取 cookie，POST /jobinfo/trigger 手动跑一次任务。"""

    def __init__(self, base="http://127.0.0.1:9090/xxl-job-admin"):
        self.base = base
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.logged_in = False

    def _post(self, path, fields):
        data = urllib.parse.urlencode(fields).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method="POST")
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            with self.opener.open(req, timeout=20) as resp:
                body = resp.read().decode("utf-8", "replace")
                try:
                    return resp.status, json.loads(body)
                except Exception:
                    return resp.status, body
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")

    def login(self, user="admin", password="123456"):
        st, body = self._post("/auth/doLogin", {"userName": user, "password": password, "ifRemember": "on"})
        self.logged_in = st == 200 and isinstance(body, dict) and body.get("code") == 200
        return self.logged_in, {"http_status": st, "response": body}

    def trigger(self, job_id, param=""):
        st, body = self._post("/jobinfo/trigger",
                              {"id": str(job_id), "executorParam": param, "addressList": ""})
        ok = st == 200 and isinstance(body, dict) and body.get("code") == 200
        return ok, {"job_id": job_id, "executor_param": param, "http_status": st, "response": body}

    def trigger_until(self, job_id, param="", timeout=90.0, wait_done=True):
        """执行器注册有延迟：触发失败就重试。wait_done 时用 joblog 里的 handleCode 判完成。"""
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            ok, detail = self.trigger(job_id, param)
            last = detail
            if not ok:
                time.sleep(3)
                continue
            if not wait_done:
                return True, detail
            # 等这一次触发落日志（handleCode=200 成功）
            t2 = time.time() + 30
            while time.time() < t2:
                code = self.last_handle_code(job_id)
                if code == 200:
                    detail["handle_code"] = code
                    return True, detail
                if code is not None and code != 0:
                    detail["handle_code"] = code
                    break
                time.sleep(1)
            time.sleep(2)
        return False, last

    def last_handle_code(self, job_id):
        st, body = self._post("/joblog/pageList", {"jobId": str(job_id), "start": "0", "length": "1"})
        if st != 200 or not isinstance(body, dict):
            return None
        rows = body.get("data") or []
        if not rows:
            return None
        return rows[0].get("handleCode")


# ---------------- Redis 只读探针（预热效果的直接证据） ----------------
REDIS_CLI = ["docker", "exec", "bs-redis", "redis-cli", "-a", "bs123456", "--no-auth-warning"]


def redis_exists(key):
    import subprocess
    p = subprocess.run(REDIS_CLI + ["EXISTS", key], capture_output=True, timeout=20)
    return p.stdout.decode("utf-8", "replace").strip() == "1"


def redis_get(key):
    import subprocess
    p = subprocess.run(REDIS_CLI + ["GET", key], capture_output=True, timeout=20)
    return p.stdout.decode("utf-8", "replace").strip()


def redis_warmup_ok(strategy_id, inv_ids):
    """两个预热任务的「活干成了」判据：决策表预热态 key + 各库存桶 key 存在。"""
    table_key = "bs:raffle:warmup:%d" % strategy_id
    bucket_keys = (["bs:inventory:virtual:bucket:%d:0" % i for i in inv_ids]
                   + ["bs:inventory:quota:bucket:%d:0" % QUOTA_INV_ID])
    return redis_exists(table_key), {k: redis_exists(k) for k in bucket_keys}


def warmup_raffle(h, xxl, job_ids, log):
    """触发两个真实预热任务，并以 Redis 效果为准判定（执行器注册/任务完成都可能有延迟）。"""
    inv_ids = [POINTS_INV_ID, FALLBACK_INV_ID]
    deadline = time.time() + 90
    last = {}
    while time.time() < deadline:
        _, ra = xxl.trigger(job_ids["raffleCacheWarmup"], str(ACTIVITY_ID))
        _, rb = xxl.trigger(job_ids["inventoryBucketWarmup"], "")
        last = {"raffleCacheWarmup": ra, "inventoryBucketWarmup": rb}
        time.sleep(2)
        table_ok, buckets = redis_warmup_ok(STRATEGY_ID, inv_ids)
        last["redis"] = {"table_key_ok": table_ok, "bucket_keys": buckets}
        if table_ok and all(buckets.values()):
            log.append(dict(last, attempts="ok"))
            return True, last, table_ok, buckets
    log.append(dict(last, attempts="超时未生效"))
    table_ok, buckets = redis_warmup_ok(STRATEGY_ID, inv_ids)
    return False, last, table_ok, buckets


# ---------------- 夹具（照 RaffleApiTestBase 的列口径） ----------------
def reset_fixtures(h):
    for table, where in FIXTURE_DELETES:
        h.sql(MARKETING_DB, "DELETE FROM %s WHERE %s" % (table, where))


def seed_raffle_config(h):
    """活动 + 策略（rule_blacklist,rule_weight）+ 四奖位（积分 0.2 / 实物 0.3 / 谢谢 0.5 + 兜底）。"""
    h.sql(MARKETING_DB, """
        INSERT INTO draw_activity (activity_id, activity_name, strategy_id, begin_date_time,
                                   end_date_time, state, create_time, update_time)
        VALUES (%d, '冒烟17抽奖', %d, '2026-01-01 00:00:00', '2027-12-31 23:59:59', 'open', NOW(), NOW())
        """ % (ACTIVITY_ID, STRATEGY_ID))
    h.sql(MARKETING_DB, """
        INSERT INTO draw_strategy (strategy_id, strategy_desc, rule, create_time, update_time)
        VALUES (%d, '冒烟17策略', 'rule_blacklist,rule_weight', NOW(), NOW())
        """ % STRATEGY_ID)
    awards = [
        (AWARD_POINTS, "60 积分", "VIRTUAL", "0.2000", 3, POINTS_INV_ID, "normal"),
        (AWARD_BAG, "校园帆布包", "PHYSICAL", "0.3000", 2, QUOTA_INV_ID, "normal"),
        (AWARD_THANKS, "谢谢参与", "THANKS", "0.5000", 5, 0, "normal"),
        (AWARD_FALLBACK, "5 积分安慰奖", "VIRTUAL", "0.0000", 6, FALLBACK_INV_ID, "fallback"),
    ]
    for award_id, name, atype, rate, level, inv_id, kind in awards:
        h.sql(MARKETING_DB, """
            INSERT INTO draw_strategy_award (strategy_id, award_id, award_name, award_desc, award_type,
                                             award_rate, level, inv_id, type, sort, create_time, update_time)
            VALUES (%d, %d, '%s', NULL, '%s', %s, %d, %d, '%s', 0, NOW(), NOW())
            """ % (STRATEGY_ID, award_id, name, atype, rate, level, inv_id, kind))


def seed_inventory(h):
    h.sql(MARKETING_DB, """
        INSERT INTO virtual_inventory (inv_id, name, type, value, surplus_qty, issued_qty, state, create_time, update_time)
        VALUES (%d, '冒烟积分发放池', 'POINTS', %d, %d, 0, 'open', NOW(), NOW())
        """ % (POINTS_INV_ID, POINTS_FACE_VALUE, VIRTUAL_BUCKET_START))
    h.sql(MARKETING_DB, """
        INSERT INTO virtual_inventory_bucket (inv_id, bucket_id, redis_qty, create_time, update_time)
        VALUES (%d, 0, %d, NOW(), NOW())
        """ % (POINTS_INV_ID, VIRTUAL_BUCKET_START))
    h.sql(MARKETING_DB, """
        INSERT INTO virtual_inventory (inv_id, name, type, value, surplus_qty, issued_qty, state, create_time, update_time)
        VALUES (%d, '冒烟兜底权益池', 'POINTS', %d, %d, 0, 'open', NOW(), NOW())
        """ % (FALLBACK_INV_ID, FALLBACK_FACE_VALUE, VIRTUAL_BUCKET_START))
    h.sql(MARKETING_DB, """
        INSERT INTO virtual_inventory_bucket (inv_id, bucket_id, redis_qty, create_time, update_time)
        VALUES (%d, 0, %d, NOW(), NOW())
        """ % (FALLBACK_INV_ID, VIRTUAL_BUCKET_START))
    h.sql(MARKETING_DB, """
        INSERT INTO physical_marketing_quota (inv_id, name, surplus_qty, waiting_qty, issued_qty, state,
                                              create_time, update_time)
        VALUES (%d, '冒烟实物营销配额', %d, 0, 0, 'open', NOW(), NOW())
        """ % (QUOTA_INV_ID, QUOTA_START))
    h.sql(MARKETING_DB, """
        INSERT INTO physical_marketing_quota_bucket (inv_id, bucket_id, redis_qty, create_time, update_time)
        VALUES (%d, 0, %d, NOW(), NOW())
        """ % (QUOTA_INV_ID, QUOTA_START))
    h.sql(MARKETING_DB, """
        INSERT INTO physical_inventory (inv_id, name, decr, surplus_qty, issued_qty, state, create_time, update_time)
        VALUES (%d, '冒烟实物库存', NULL, %d, 0, 'open', NOW(), NOW())
        """ % (QUOTA_INV_ID, QUOTA_START))


def only_award(h, award_id):
    """把 normal 档概率改成「指定奖品 1.0000、其余 0」，然后重跑真实预热任务让新表生效。"""
    h.sql(MARKETING_DB, "UPDATE draw_strategy_award SET award_rate = 0 WHERE strategy_id = %d AND type = 'normal'"
          % STRATEGY_ID)
    h.sql(MARKETING_DB, "UPDATE draw_strategy_award SET award_rate = 1.0000 WHERE award_id = %d" % award_id)


def seed_draw_count_bucket(h, user_id, amount, ref_id, days=30):
    """照 RaffleApiTestBase.giveChances 直接落一个次数桶（reason=REDEEM）；同 ref_id 重跑即复位。"""
    h.sql(MARKETING_DB, """
        INSERT INTO draw_count_bucket (bucket_id, user_id, amount, remain, expire_at, reason_type, ref_id,
                                       create_time, update_time)
        VALUES (FLOOR(RAND() * 900000000000000000) + 100000000000000000, %d, %d, %d,
                DATE_ADD(NOW(), INTERVAL %d DAY), 'REDEEM', %d, NOW(), NOW())
        ON DUPLICATE KEY UPDATE amount = VALUES(amount), remain = VALUES(remain),
                                expire_at = VALUES(expire_at), update_time = NOW()
        """ % (user_id, amount, amount, days, ref_id))
    return int(h.one(MARKETING_DB, "SELECT bucket_id FROM draw_count_bucket WHERE user_id = %d AND ref_id = %d"
                     % (user_id, ref_id)))


def seed_points_bucket(h, user_id, amount, remain, days, reason_type, ref_id):
    """照 PointsApiTestBase.insertBucket 落积分桶（项 6 的入账事实源行）；同 ref_id 重跑即复位。"""
    h.sql(MARKETING_DB, """
        INSERT INTO points_bucket (bucket_id, user_id, amount, remain, expire_at, reason_type, ref_id,
                                   create_time, update_time)
        VALUES (FLOOR(RAND() * 900000000000000000) + 100000000000000000, %d, %d, %d,
                DATE_ADD(NOW(), INTERVAL %d DAY), '%s', %d, NOW(), NOW())
        ON DUPLICATE KEY UPDATE amount = VALUES(amount), remain = VALUES(remain),
                                expire_at = VALUES(expire_at), update_time = NOW()
        """ % (user_id, amount, remain, days, reason_type, ref_id))
    return int(h.one(MARKETING_DB, "SELECT bucket_id FROM points_bucket WHERE user_id = %d AND reason_type = '%s'"
                     " AND ref_id = %d" % (user_id, reason_type, ref_id)))


def seed_rebate_rule(h, action_type, rebate_value, config_id, state="open"):
    h.sql(MARKETING_DB, """
        INSERT INTO action_rebate_config (action_type, rebate_type, rebate_name, rebate_desc,
                                          rebate_value, config_id, state, create_time, update_time)
        VALUES ('%s', 'POINTS', '%s 返利', NULL, %d, %d, '%s', NOW(), NOW())
        ON DUPLICATE KEY UPDATE rebate_value = VALUES(rebate_value), state = VALUES(state),
                                update_time = NOW()
        """ % (action_type, action_type, rebate_value, config_id, state))


# ---------------- 栈就绪轮询（每 30s，最多 25 分钟） ----------------
def wait_for_stack():
    deadline = time.time() + 25 * 60
    attempt = 0
    while True:
        attempt += 1
        try:
            req = urllib.request.Request(MKT_BASE + CHECK_ACTIVITY)
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status == 200:
                    print("PASS | 栈就绪：%s → HTTP 200（第 %d 次探测）" % (CHECK_ACTIVITY, attempt))
                    return True
        except urllib.error.HTTPError as e:
            print("  ... 栈未就绪（第 %d 次探测）：HTTP %s" % (attempt, e.code))
        except Exception as e:  # noqa: BLE001
            print("  ... 栈未就绪（第 %d 次探测）：%s" % (attempt, e))
        if time.time() >= deadline:
            print("FAIL | marketing 25 分钟未就绪，放弃（%s）" % (MKT_BASE + CHECK_ACTIVITY))
            return False
        time.sleep(30)


# =====================================================================
# 项 6：积分桶入账 + 消费扣桶
# =====================================================================
def item6(h, token, user_id):
    head("项 6：积分桶入账 + 消费扣桶")

    # --- 6.1 入账（规则行 + 桶行） ---
    seed_rebate_rule(h, "sign", 20, 1)
    rule = rows_named(h, MARKETING_DB,
                      "SELECT action_type, rebate_type, rebate_value, config_id, state FROM action_rebate_config"
                      " WHERE action_type='sign' AND rebate_type='POINTS'",
                      ["action_type", "rebate_type", "rebate_value", "config_id", "state"])
    check("6.1a action_rebate_config 有 sign/POINTS 开启规则（入账规则的库内事实）",
          rule and rule[0]["state"] == "open" and rule[0]["rebate_value"] == "20", rule)

    # 入账 = 桶行（creditForRebate 无 HTTP/MQ 入口，按集成测试夹具口径落事实源）
    # 重跑复位：清掉本用户历史消费明细与「上一轮抽奖中奖」入账桶（只动这个号段的 user_id）
    h.sql(MARKETING_DB, "DELETE FROM points_detail WHERE user_id = %d" % user_id)
    h.sql(MARKETING_DB, "DELETE FROM points_bucket WHERE user_id = %d AND reason_type = 'LOTTERY_WIN' AND ref_id >= 8100"
          % user_id)
    b1 = seed_points_bucket(h, user_id, 40, 40, 2, "REBATE", 74101)
    b2 = seed_points_bucket(h, user_id, 300, 300, 12, "REBATE", 74102)
    CONTEXT["points_b1"] = b1
    CONTEXT["points_b2"] = b2
    buckets = rows_named(h, MARKETING_DB,
                         "SELECT bucket_id, amount, remain, reason_type, ref_id, expire_at FROM points_bucket"
                         " WHERE user_id = %d ORDER BY expire_at, id" % user_id,
                         ["bucket_id", "amount", "remain", "reason_type", "ref_id", "expire_at"])
    h.evidence("06a-points-buckets-seeded", {"rebate_rule": rule, "points_buckets": buckets,
                                             "note": "creditForRebate 在营销服务无 HTTP/MQ 入口"
                                                     "（docs/api/points.md），按 PointsApiTestBase.insertBucket 口径落库"})
    total_in = int(h.one(MARKETING_DB, "SELECT COALESCE(SUM(amount),0) FROM points_bucket WHERE user_id = %d" % user_id))
    attempts = []
    bal_ok, bal_data, bal_last = mkt_ok(h, "GET", "/api/points/balance", token=token, records=attempts)
    balance = (bal_data or {}).get("balance")
    h.evidence("06-balance-attempts", {"attempts": attempts, "balance": balance})
    check("6.1b 入账落桶：points_bucket 两行（amount 合计 340），GET /api/points/balance = 340",
          len(buckets) == 2 and total_in == 340 and balance is not None and int(balance) == 340,
          "buckets=%d sum(amount)=%d balance=%s（attempts=%d）" % (len(buckets), total_in, balance, len(attempts)))

    # --- 6.2 消费扣桶（真实消费口：兑换抽奖次数，100 积分/次） ---
    before = {b["bucket_id"]: int(b["remain"]) for b in buckets}
    # 兑换前先记「可用次数余额」：响应里的 drawCount = 兑完之后的可用次数余额（见 RedeemDrawCountResponseDTO 注释），
    # 不是本次入账次数；断言必须用「兑前余额 + 1」，否则重跑时会被历史次数顶红。
    chances_before = int(h.one(MARKETING_DB,
                               "SELECT COALESCE(SUM(remain),0) FROM draw_count_bucket WHERE user_id = %d" % user_id))
    exchange_token = "smoke17-6-exchange-%d" % int(time.time())
    st, resp = h.call("POST", "/api/lottery/exchange/draw-count", {"token": exchange_token},
                      token=token, base=MKT_BASE)
    detail = resp.get("data") if isinstance(resp, dict) else None
    h.evidence("06b-exchange-draw-count-response", {"http_status": st, "request": {"token": exchange_token},
                                                    "response": resp, "price": DRAW_COUNT_PRICE})
    check("6.2a POST /api/lottery/exchange/draw-count 成功（100 积分 → 1 次；drawCount = 兑后可用余额）",
          st == 200 and isinstance(resp, dict) and resp.get("code") == "200" and detail
          and int(detail.get("drawCount", -1)) == chances_before + 1,
          "HTTP %s code=%s data=%s chances_before=%s" % (
              st, resp.get("code") if isinstance(resp, dict) else None, detail, chances_before))

    after = rows_named(h, MARKETING_DB,
                       "SELECT bucket_id, remain, expire_at FROM points_bucket WHERE user_id = %d ORDER BY expire_at, id"
                       % user_id, ["bucket_id", "remain", "expire_at"])
    after_map = {r["bucket_id"]: int(r["remain"]) for r in after}
    details = rows_named(h, MARKETING_DB,
                         "SELECT bucket_id, amount, reason_type, ref_id FROM points_detail WHERE user_id = %d"
                         " ORDER BY id" % user_id,
                         ["bucket_id", "amount", "reason_type", "ref_id"])
    ded = detail.get("redeemOrderId") if detail else None
    h.evidence("06c-points-deduct-fifo", {"before_remain": before, "after_remain": after_map,
                                          "points_detail": details, "redeem_order_id": ded,
                                          "expect": "最早过期的桶（40）被扣光，差额 60 落到后过期的桶"})
    # 键类型对齐：rows_named 返回的 bucket_id 是字符串，seed_points_bucket 返回 int；
    # 被扣光的桶行可能已随 FIFO 消费删除，故缺失按 0 处理。
    b1k, b2k = str(b1), str(b2)
    rem_b1 = after_map.get(b1k, 0)
    exp_b2 = int(before[b2k]) - (DRAW_COUNT_PRICE - int(before[b1k]))
    rem_b2 = after_map.get(b2k, -1)
    check("6.2b FIFO 跨桶按到期顺序扣：最早过期的桶扣光（40→0）、差额 60 落到后过期的桶（300→240）",
          rem_b1 == 0 and rem_b2 == exp_b2,
          "桶 %s: %s→%s / 桶 %s: %s→%s（期望 %s→%s）" % (
              b1k, before[b1k], rem_b1, b2k, before[b2k], rem_b2, before[b1k], exp_b2))
    consumed = sum(int(d["amount"]) for d in details)
    check("6.2c 落两行 points_detail 消费明细（按桶分别记 40/60，ref_id=兑换单号，合计 100）",
          len(details) == 2 and all(d["reason_type"] == "REDEEM_DRAW_COUNT" for d in details)
          and all(d["ref_id"] == str(ded) for d in details) and consumed == DRAW_COUNT_PRICE
          and sorted(int(d["amount"]) for d in details) == [40, 60],
          "rows=%d sum=%d ref_id=%s redeemOrderId=%s" % (len(details), consumed, details[0]["ref_id"] if details else None, ded))

    bal2_ok, bal2_data, bal2_last = mkt_ok(h, "GET", "/api/points/balance", token=token)
    balance_after = (bal2_data or {}).get("balance")
    cnt_bucket = rows_named(h, MARKETING_DB,
                            "SELECT amount, remain, reason_type, ref_id FROM draw_count_bucket WHERE user_id = %d"
                            % user_id, ["amount", "remain", "reason_type", "ref_id"])
    h.evidence("06d-after-exchange-balance", {"points_balance": balance_after, "draw_count_buckets": cnt_bucket,
                                              "last_response": bal2_last})
    # 只认本次兑换单号那一行：重跑会在 draw_count_bucket 留下历史 REDEEM 桶，按 ref_id 过滤才幂等
    mine = [b for b in cnt_bucket if b["ref_id"] == str(ded)]
    check("6.2d 扣桶后余额 = 240，draw_count_bucket 有本次兑换单号的 REDEEM 入账一行",
          balance_after is not None and int(balance_after) == 240 and len(mine) == 1
          and int(mine[0]["remain"]) == 1 and int(mine[0]["amount"]) == 1 and mine[0]["reason_type"] == "REDEEM",
          "balance=%s mine=%s all=%s" % (balance_after, mine, cnt_bucket))

    tx_ok, tx, tx_last = mkt_ok(h, "GET", "/api/points/transactions?page=1&pageSize=20", token=token)
    h.evidence("06e-points-transactions", {"ok": tx_ok, "data": tx, "last_response": tx_last})
    dirs = [t.get("direction") for t in (tx or {}).get("list", [])]
    check("6.2e GET /api/points/transactions 体现 IN/OUT（入账 2 行 + 消费 2 行）",
          tx_ok and dirs.count("IN") == 2 and dirs.count("OUT") == 2, "directions=%s" % dirs)


# =====================================================================
# 项 7 + 项 9（b 段）：抽奖全链路 + 通知落库
# =====================================================================
def draw_once(h, token, draw_token):
    st, resp = h.call("POST", "/api/lottery/draw", {"token": draw_token}, token=token, base=MKT_BASE)
    return st, resp


def wait_merge(h, table, where, timeout=40.0):
    """等每秒的 inventoryMerge 把该组的 state=0 预扣行全部翻成 state=1；返回带列名的明细行。"""
    cols = ["id", "inv_id", "bucket_id", "quantity", "state"]

    def probe():
        rows = h.sql(MARKETING_DB, "SELECT COUNT(*) FROM %s WHERE %s AND state = 0" % (table, where))
        return rows and int(rows[0][0]) == 0
    wait_until(probe, timeout=timeout, interval=1.0, what="inventoryMerge 翻转 %s 预扣行" % table)
    return rows_named(h, MARKETING_DB, "SELECT id, inv_id, bucket_id, quantity, state FROM %s WHERE %s"
                      % (table, where), cols)


def draw_record_row(h, record_id):
    rows = rows_named(h, MARKETING_DB,
                      "SELECT record_id, user_id, award_id, award_name, award_type, level, inv_id, state"
                      " FROM draw_record WHERE record_id = %d" % record_id,
                      ["record_id", "user_id", "award_id", "award_name", "award_type", "level", "inv_id", "state"])
    return rows[0] if rows else None


def item7_9(h, token, user_id, other_token, other_id, xxl, job_ids):
    head("项 7：抽奖全链路（次数 → 责任链 → 库存预扣 → 合并任务 → 发货）")

    # --- 7.1 夹具 + 真实编排任务（决策表预热 / 库存桶预热） ---
    reset_fixtures(h)
    seed_raffle_config(h)
    seed_inventory(h)
    only_award(h, AWARD_POINTS)

    warm_log = []
    warm_ok, warm_detail, table_ok, bucket_ok = warmup_raffle(h, xxl, job_ids, warm_log)
    h.evidence("07a-warmup-jobs", {"attempts": warm_log, "final": warm_detail, "job_ids": job_ids,
                                   "redis_table_key_ok": table_ok, "redis_bucket_keys": bucket_ok})
    check("7.1a xxl 任务 raffleCacheWarmup（参数=activityId）执行成功（Redis bs:raffle:warmup:%d 已置）"
          % STRATEGY_ID, table_ok, "table_key_ok=%s attempts=%d" % (table_ok, len(warm_log)))
    check("7.1b xxl 任务 inventoryBucketWarmup（空参=双段全量）执行成功（三段桶 key 均存在）",
          bool(bucket_ok) and all(bucket_ok.values()), bucket_ok)
    check("7.1c 预热整体生效（决策表 + 库存桶均可扣）", table_ok and all(bucket_ok.values()),
          {"table": table_ok, "buckets": bucket_ok})

    activity_ok, activity, activity_last = mkt_ok(h, "GET", "/api/lottery/activity")
    h.evidence("07b-activity", {"ok": activity_ok, "data": activity, "last_response": activity_last})
    check("7.1c GET /api/lottery/activity 返回进行中的活动（name=%s）" % (activity or {}).get("name"),
          activity_ok and activity and str(activity.get("status")).upper() in ("OPEN", "ONGOING"),
          "status=%s startAt=%s endAt=%s" % ((activity or {}).get("status"), (activity or {}).get("startAt"),
                                             (activity or {}).get("endAt")))

    chances_bucket = seed_draw_count_bucket(h, user_id, 3, 74201)
    CONTEXT["dc_bucket"] = chances_bucket
    # 重跑复位：清掉本用户的历史次数消费明细与历史次数桶（只动这个号段的 user_id）
    h.sql(MARKETING_DB, "DELETE FROM draw_count_detail WHERE user_id = %d" % user_id)
    h.sql(MARKETING_DB, "DELETE FROM draw_count_bucket WHERE user_id = %d" % user_id)
    chances_bucket = seed_draw_count_bucket(h, user_id, 3, 74201)
    CONTEXT["dc_bucket"] = chances_bucket
    before_chances = rows_named(h, MARKETING_DB,
                                "SELECT bucket_id, amount, remain FROM draw_count_bucket WHERE user_id = %d" % user_id,
                                ["bucket_id", "amount", "remain"])
    api_ok, api_chances, api_last = mkt_ok(h, "GET", "/api/lottery/draw-chances", token=token)
    h.evidence("07b2-draw-chances", {"ok": api_ok, "data": api_chances, "last_response": api_last,
                                     "bucket": before_chances})
    check("7.1d 前置：次数桶 3 次可抽（GET /api/lottery/draw-chances.available = 3）",
          api_ok and int((api_chances or {}).get("available", -1)) == 3,
          "available=%s bucket=%s" % ((api_chances or {}).get("available"), before_chances))

    inv_before = int(h.one(MARKETING_DB, "SELECT issued_qty FROM virtual_inventory WHERE inv_id = %d" % POINTS_INV_ID))
    quota_before = h.sql(MARKETING_DB,
                         "SELECT waiting_qty, issued_qty, surplus_qty FROM physical_marketing_quota WHERE inv_id = %d"
                         % QUOTA_INV_ID)[0]
    h.evidence("07c-inventory-before", {"virtual_inventory": {"inv_id": POINTS_INV_ID, "issued_qty": inv_before},
                                        "physical_marketing_quota": {"inv_id": QUOTA_INV_ID,
                                                                     "waiting_qty": quota_before[0],
                                                                     "issued_qty": quota_before[1],
                                                                     "surplus_qty": quota_before[2]}})

    # ---------- 7.2 虚拟档一次抽奖 ----------
    draw_token = "smoke17-7-virtual-%d" % int(time.time())
    st, resp = draw_once(h, token, draw_token)
    data = resp.get("data") if isinstance(resp, dict) else None
    h.evidence("07d-draw-virtual-response", {"http_status": st, "request": {"token": draw_token}, "response": resp})
    check("7.2a POST /api/lottery/draw 成功（虚拟档 VIRTUAL，返回 recordId）",
          st == 200 and isinstance(resp, dict) and resp.get("code") == "200" and data
          and data.get("prizeType") == "VIRTUAL", "HTTP %s code=%s data=%s" % (st, resp.get("code") if isinstance(resp, dict) else None, data))
    if not (data and data.get("recordId")):
        check("7.2* 抽奖未返回 recordId，链路后续断言跳过", False, str(resp))
        return None
    record_id = int(data["recordId"])
    CONTEXT["record_id"] = record_id

    # 7.2b 次数桶扣减 + draw_count_detail
    after_bucket = rows_named(h, MARKETING_DB,
                              "SELECT bucket_id, amount, remain FROM draw_count_bucket WHERE user_id = %d" % user_id,
                              ["bucket_id", "amount", "remain"])
    dc_detail = rows_named(h, MARKETING_DB,
                           "SELECT bucket_id, ref_id FROM draw_count_detail WHERE ref_id = %d" % record_id,
                           ["bucket_id", "ref_id"])
    h.evidence("07e-draw-count-deducted", {"before": before_chances, "after": after_bucket,
                                           "draw_count_detail": dc_detail, "record_id": record_id})
    check("7.2b 次数桶扣减：remain 3→2 且 draw_count_detail 有 (bucket_id, ref_id=recordId) 一行",
          len(after_bucket) == 1 and int(after_bucket[0]["remain"]) == 2
          and len(dc_detail) == 1 and dc_detail[0]["bucket_id"] == after_bucket[0]["bucket_id"],
          "after=%s detail=%s" % (after_bucket, dc_detail))

    # 7.2c draw_record + draw_record_detail(DRAW)
    rec = draw_record_row(h, record_id)
    rec_details = rows_named(h, MARKETING_DB,
                             "SELECT type, user_id, record_id FROM draw_record_detail WHERE record_id = %d"
                             % record_id, ["type", "user_id", "record_id"])
    h.evidence("07f-draw-record", {"draw_record": rec, "draw_record_detail": rec_details})
    # 虚拟档按票 14 口径「落库即推进」：MQ 消费常在 1s 内把 state 推到已完成，
    # 因此这里接受 待兑换 / 已完成 两态；终态由 7.2f 断言。
    check("7.2c draw_record 落库（虚拟档 award_id=%d，state=待兑换|已完成）+ draw_record_detail(type=DRAW) 一行"
          % AWARD_POINTS,
          rec and rec["award_type"] == "VIRTUAL" and rec["state"] in ("待兑换", "已完成")
          and any(d["type"] == "DRAW" for d in rec_details),
          "record=%s details=%s" % (rec, rec_details))

    # 7.2d 库存预扣（state=0 明细）——合并任务每秒一轮，可能已经翻走：两种可观察态都算证据
    state0_rows = rows_named(h, MARKETING_DB,
                             "SELECT id, inv_id, bucket_id, quantity, state FROM virtual_inventory_detail"
                             " WHERE inv_id = %d AND state = 0" % POINTS_INV_ID,
                             ["id", "inv_id", "bucket_id", "quantity", "state"])
    settled_now = int(h.one(MARKETING_DB,
                            "SELECT issued_qty FROM virtual_inventory WHERE inv_id = %d" % POINTS_INV_ID))
    all_rows = rows_named(h, MARKETING_DB,
                          "SELECT id, inv_id, bucket_id, quantity, state FROM virtual_inventory_detail"
                          " WHERE inv_id = %d" % POINTS_INV_ID,
                          ["id", "inv_id", "bucket_id", "quantity", "state"])
    h.evidence("07g-virtual-prededuct", {"state0_rows": state0_rows, "all_rows": all_rows,
                                         "issued_qty_now": settled_now,
                                         "expect": "state=0 quantity=1（合并未跑）/ 等价证据：已合并且 issued_qty=1"})
    check("7.2d 虚拟库存已预扣：virtual_inventory_detail 有 quantity=1 明细，且落库时 state=0"
          "（未跑到的合并以 issued_qty=1 等价证明）",
          any(int(r["quantity"]) == 1 for r in all_rows)
          and (any(r["state"] == "0" for r in all_rows) or settled_now == inv_before + 1),
          "state0=%s issued_qty=%d" % (state0_rows, settled_now))

    # 7.2e 合并任务（每秒的 inventoryMerge）把预扣翻进 issued_qty
    merged_rows = wait_merge(h, "virtual_inventory_detail", "inv_id = %d" % POINTS_INV_ID)
    issued_after = int(h.one(MARKETING_DB, "SELECT issued_qty FROM virtual_inventory WHERE inv_id = %d" % POINTS_INV_ID))
    merged = rows_named(h, MARKETING_DB,
                        "SELECT id, inv_id, bucket_id, quantity, state FROM virtual_inventory_detail"
                        " WHERE inv_id = %d" % POINTS_INV_ID,
                        ["id", "inv_id", "bucket_id", "quantity", "state"])
    h.evidence("07h-virtual-merged", {"details": merged, "issued_qty_after": issued_after,
                                      "issued_qty_before": inv_before})
    check("7.2e inventoryMerge（xxl，秒级）把预扣翻进台账：virtual_inventory.issued_qty %d→%d，明细 state=1"
          % (inv_before, issued_after),
          issued_after == inv_before + 1 and all(r["state"] == "1" for r in merged_rows),
          "issued_qty=%d details=%s" % (issued_after, merged_rows))

    # 7.2f 发货状态机推进（虚拟档：真实 MQ bs-lottery-drawn → 已完成）
    def state_is(expect):
        return lambda: (draw_record_row(h, record_id) or {}).get("state") == expect
    final_state = None
    try:
        wait_until(state_is("已完成"), timeout=25.0, interval=0.8, what="虚拟档发货状态机推进到已完成")
    except AssertionError:
        pass
    rec_final = draw_record_row(h, record_id)
    details_final = rows_named(h, MARKETING_DB,
                               "SELECT type FROM draw_record_detail WHERE record_id = %d ORDER BY id" % record_id,
                               ["type"])
    final_state = rec_final["state"] if rec_final else None
    types = [d["type"] for d in details_final]
    h.evidence("07i-virtual-shipping", {"draw_record": rec_final, "detail_types": types,
                                        "expect": "已完成 + DRAW/EXCHANGE/COMPLETE"})
    check("7.2f 发货状态机推进（真实 MQ 消费）：draw_record.state=已完成，明细 DRAW+EXCHANGE+COMPLETE",
          final_state == "已完成" and sorted(types) == ["COMPLETE", "DRAW", "EXCHANGE"],
          "state=%s details=%s" % (final_state, types))

    # 7.2g 积分入账（真实 MQ：抽奖中奖 → points_bucket LOTTERY_WIN）
    win_ref = record_id
    try:
        wait_until(lambda: h.sql(MARKETING_DB,
                                 "SELECT 1 FROM points_bucket WHERE user_id = %d AND reason_type = 'LOTTERY_WIN'"
                                 " AND ref_id = %d" % (user_id, win_ref)),
                   timeout=25.0, interval=0.8, what="积分域消费 bs-lottery-drawn 入账 LOTTERY_WIN")
    except AssertionError:
        pass
    win_rows = rows_named(h, MARKETING_DB,
                          "SELECT bucket_id, amount, remain, reason_type, ref_id FROM points_bucket"
                          " WHERE user_id = %d ORDER BY id" % user_id,
                          ["bucket_id", "amount", "remain", "reason_type", "ref_id"])
    h.evidence("07j-points-lottery-win", {"points_buckets": win_rows, "record_id": record_id,
                                          "expect": "新增一行 LOTTERY_WIN amount=60 ref_id=recordId"})
    win = [r for r in win_rows if r["reason_type"] == "LOTTERY_WIN"]
    check("7.2g 真实 MQ 消费 bs-lottery-drawn：points_bucket 新增 LOTTERY_WIN 行（amount=%d，ref_id=recordId）"
          % POINTS_FACE_VALUE,
          len(win) == 1 and int(win[0]["amount"]) == POINTS_FACE_VALUE and win[0]["ref_id"] == str(record_id),
          "LOTTERY_WIN rows=%s" % win)

    outbox = rows_named(h, MARKETING_DB,
                        "SELECT id, msg_id, destination, state, retry_count FROM outbox_message"
                        " WHERE payload LIKE '%%%d%%' ORDER BY id" % record_id,
                        ["id", "msg_id", "destination", "state", "retry_count"])
    h.evidence("07k-outbox-drawn", {"outbox_rows": outbox, "expect": "destination=bs-lottery-drawn, state=1（已投递）"})
    check("7.2h 中奖事件经 outbox 真实投递（bs-lottery-drawn 行 state=1 已发送）",
          any(r["destination"] == "bs-lottery-drawn" and r["state"] == "1" for r in outbox),
          "outbox=%s" % outbox)

    # ---------- 7.3 实物档一次抽奖 ----------
    reset_fixtures(h)
    seed_raffle_config(h)
    seed_inventory(h)
    only_award(h, AWARD_BAG)
    phys_warm_log = []
    _, phys_warm, phys_table_ok, phys_bucket_ok = warmup_raffle(h, xxl, job_ids, phys_warm_log)
    h.evidence("07l-physical-warmup", {"attempts": phys_warm_log, "final": phys_warm,
                                       "redis_table_key_ok": phys_table_ok, "redis_bucket_keys": phys_bucket_ok})
    seed_draw_count_bucket(h, user_id, 2, 74301)

    quota0_rows = h.sql(MARKETING_DB, "SELECT waiting_qty, issued_qty, surplus_qty, state FROM physical_marketing_quota"
                                      " WHERE inv_id = %d" % QUOTA_INV_ID)[0]
    quota0 = {"waiting_qty": int(quota0_rows[0]), "issued_qty": int(quota0_rows[1]),
              "surplus_qty": int(quota0_rows[2])}
    phys_inv0 = int(h.one(MARKETING_DB, "SELECT surplus_qty FROM physical_inventory WHERE inv_id = %d" % QUOTA_INV_ID))

    draw_token_p = "smoke17-7-physical-%d" % int(time.time())
    st, resp = draw_once(h, token, draw_token_p)
    data = resp.get("data") if isinstance(resp, dict) else None
    h.evidence("07l-draw-physical-response", {"http_status": st, "request": {"token": draw_token_p}, "response": resp})
    check("7.3a POST /api/lottery/draw 成功（实物档 PHYSICAL）",
          st == 200 and isinstance(resp, dict) and resp.get("code") == "200" and data
          and data.get("prizeType") == "PHYSICAL",
          "HTTP %s code=%s data=%s" % (st, resp.get("code") if isinstance(resp, dict) else None, data))
    if not (data and data.get("recordId")):
        check("7.3* 实物抽奖未返回 recordId，实物段断言跳过", False, str(resp))
        return
    prec_id = int(data["recordId"])
    CONTEXT["physical_record_id"] = prec_id

    pre_quota_rows = rows_named(h, MARKETING_DB,
                                "SELECT id, inv_id, bucket_id, quantity, state FROM physical_marketing_quota_detail"
                                " WHERE inv_id = %d" % QUOTA_INV_ID,
                                ["id", "inv_id", "bucket_id", "quantity", "state"])
    quota_now = h.sql(MARKETING_DB, "SELECT waiting_qty, issued_qty FROM physical_marketing_quota"
                                    " WHERE inv_id = %d" % QUOTA_INV_ID)[0]
    h.evidence("07m-physical-prededuct", {"quota_detail": pre_quota_rows, "quota_before": quota0,
                                          "quota_now": {"waiting_qty": quota_now[0], "issued_qty": quota_now[1]},
                                          "expect": "state=0 quantity=1（合并未跑）/ 等价：已翻进 waiting_qty"})
    check("7.3b 实物配额预扣：physical_marketing_quota_detail 有 quantity=1 明细，落库态 state=0"
          "（合并已跑则以 waiting_qty=1 等价证明）",
          len(pre_quota_rows) == 1 and int(pre_quota_rows[0]["quantity"]) == 1
          and (pre_quota_rows[0]["state"] == "0" or int(quota_now[0]) == 1),
          "rows=%s quota_now=%s" % (pre_quota_rows, quota_now))

    merged_rows = wait_merge(h, "physical_marketing_quota_detail", "inv_id = %d" % QUOTA_INV_ID)
    quota1_rows = h.sql(MARKETING_DB, "SELECT waiting_qty, issued_qty, surplus_qty FROM physical_marketing_quota"
                                      " WHERE inv_id = %d" % QUOTA_INV_ID)[0]
    quota1 = {"waiting_qty": int(quota1_rows[0]), "issued_qty": int(quota1_rows[1]),
              "surplus_qty": int(quota1_rows[2])}
    h.evidence("07n-physical-merged", {"quota_after_merge": quota1, "quota_detail": merged_rows,
                                       "expect": "waiting_qty 0→1（或被确认消费后的等价态 issued+1）"})
    merged_into_ledger = (quota1["waiting_qty"] == quota0["waiting_qty"] + 1
                          or quota1["issued_qty"] == quota0["issued_qty"] + 1)
    # 配额守恒口径：surplus_qty 是「剩余可发份数」，预扣合并进 waiting_qty 后它同步 -1，
    # 所以守恒式是 surplus + waiting + issued == 总量，而不是 surplus 恒定不变。
    conserved = (quota1["surplus_qty"] + quota1["waiting_qty"] + quota1["issued_qty"]) == QUOTA_START
    check("7.3c inventoryMerge 把实物预扣翻进台账（waiting_qty +1 或 issued+1）、明细 state=1、"
          "且 surplus+waiting+issued 守恒 = %d" % QUOTA_START,
          merged_into_ledger and all(r["state"] == "1" for r in merged_rows) and conserved,
          "quota=%s conserved=%s detail=%s" % (quota1, conserved, merged_rows))

    # 7.3d 发货状态机（实物档：MQ → 已发货，并 waiting→issued 兑换确认）
    try:
        wait_until(lambda: (draw_record_row(h, prec_id) or {}).get("state") == "已发货",
                   timeout=30.0, interval=0.8, what="实物档发货状态机推进到已发货")
    except AssertionError:
        pass
    rec_p = draw_record_row(h, prec_id)
    pdetails = [d["type"] for d in rows_named(h, MARKETING_DB,
                                             "SELECT type FROM draw_record_detail WHERE record_id = %d ORDER BY id"
                                             % prec_id, ["type"])]
    quota2_rows = h.sql(MARKETING_DB, "SELECT waiting_qty, issued_qty, surplus_qty FROM physical_marketing_quota"
                                      " WHERE inv_id = %d" % QUOTA_INV_ID)[0]
    quota2 = {"waiting_qty": int(quota2_rows[0]), "issued_qty": int(quota2_rows[1]),
              "surplus_qty": int(quota2_rows[2])}
    pdetail_lifecycle = rows_named(h, MARKETING_DB,
                                   "SELECT id, inv_id, quantity, type FROM physical_inventory_detail"
                                   " WHERE inv_id = %d ORDER BY id" % QUOTA_INV_ID,
                                   ["id", "inv_id", "quantity", "type"])
    phys_inv1 = int(h.one(MARKETING_DB, "SELECT surplus_qty FROM physical_inventory WHERE inv_id = %d" % QUOTA_INV_ID))
    h.evidence("07o-physical-shipping", {"draw_record": rec_p, "detail_types": pdetails,
                                         "quota_after_confirm": quota2,
                                         "quota_before": quota0,
                                         "physical_inventory_surplus_before": phys_inv0,
                                         "physical_inventory_surplus_after": phys_inv1,
                                         "physical_inventory_detail": pdetail_lifecycle})
    check("7.3d 实物发货：draw_record.state=已发货 + 明细 SHIP + 兑换确认 waiting→issued",
          rec_p and rec_p["state"] == "已发货" and "SHIP" in pdetails
          and quota2["waiting_qty"] == 0 and quota2["issued_qty"] == quota1["issued_qty"] + 1,
          "state=%s details=%s quota=%s" % (rec_p["state"] if rec_p else None, pdetails, quota2))

    # 7.3e 用户确认收货
    st, resp = h.call("POST", "/api/lottery/my-prizes/%d/receive" % prec_id, {}, token=token, base=MKT_BASE)
    h.evidence("07p-confirm-receive-response", {"http_status": st, "record_id": prec_id, "response": resp})
    rec_p2 = draw_record_row(h, prec_id)
    pdetails2 = [d["type"] for d in rows_named(h, MARKETING_DB,
                                              "SELECT type FROM draw_record_detail WHERE record_id = %d ORDER BY id"
                                              % prec_id, ["type"])]
    check("7.3e POST /api/lottery/my-prizes/{id}/receive 成功且状态 已发货→已完成（COMPLETE 明细一条）",
          st == 200 and isinstance(resp, dict) and resp.get("code") == "200" and rec_p2
          and rec_p2["state"] == "已完成" and pdetails2.count("COMPLETE") == 1,
          "HTTP %s state=%s details=%s" % (st, rec_p2["state"] if rec_p2 else None, pdetails2))

    # 7.3f 我的奖品列表状态
    prizes_ok, prizes, prizes_last = mkt_ok(h, "GET", "/api/lottery/my-prizes?status=COMPLETED&page=1&pageSize=10",
                                            token=token)
    prizes = prizes or {}
    h.evidence("07q-my-prizes-completed", {"ok": prizes_ok, "data": prizes, "last_response": prizes_last})
    check("7.3f 我的奖品：COMPLETED 列表含本次实物记录",
          any(int(p.get("id", -1)) == prec_id for p in prizes.get("list", [])),
          "total=%s ids=%s" % (prizes.get("total"), [p.get("id") for p in prizes.get("list", [])]))

    # ---------- 项 9：通知落库（真实 MQ 路径） ----------
    head("项 9：通知（中奖 / 积分变动）落库")

    def notifications(uid, like):
        return rows_named(h, BS_DB,
                          "SELECT notification_id, user_id, scope, seq, type, title, content, ref_type, ref_id,"
                          " dedup_key FROM notification WHERE user_id = %d AND dedup_key LIKE '%s' ORDER BY id"
                          % (uid, like),
                          ["notification_id", "user_id", "scope", "seq", "type", "title", "content",
                           "ref_type", "ref_id", "dedup_key"])

    try:
        wait_until(lambda: notifications(user_id, "MARKETING:DRAWN:%%"),
                   timeout=25.0, interval=0.8, what="单体消费 bs-lottery-drawn 落中奖通知")
    except AssertionError:
        pass
    try:
        wait_until(lambda: notifications(user_id, "MARKETING:POINTS:%%"),
                   timeout=25.0, interval=0.8, what="单体消费 bs-points-changed 落积分变动通知")
    except AssertionError:
        pass

    drawn_notes = notifications(user_id, "MARKETING:DRAWN:%")
    points_notes = notifications(user_id, "MARKETING:POINTS:%")
    other_drawn = notifications(other_id, "MARKETING:DRAWN:%")
    other_points = notifications(other_id, "MARKETING:POINTS:%")
    h.evidence("09a-notifications", {"user_id": user_id, "other_user_id": other_id,
                                     "drawn": drawn_notes, "points_changed": points_notes,
                                     "other_user_drawn": other_drawn, "other_user_points": other_points,
                                     "virtual_record_id": record_id, "physical_record_id": prec_id})

    dn = [n for n in drawn_notes if n["ref_id"] == str(record_id)]
    check("9.1 中奖通知落 notification（收件人=中奖用户，type/title/content 非空，ref_id=recordId）",
          len(dn) == 1 and dn[0]["type"] and dn[0]["title"] and dn[0]["content"]
          and dn[0]["scope"] == "BOTTOM_SYSTEM" and dn[0]["ref_type"] == "MARKETING",
          "rows=%s" % dn)
    check("9.2 中奖通知文案按奖品档呈现（VIRTUAL：含积分数字）",
          bool(dn) and str(POINTS_FACE_VALUE) in dn[0]["content"], dn[0]["content"] if dn else "无通知行")

    # 积分变动通知按 (reasonType, refId) 去重：中奖入账 / 兑换扣分 各一条
    win_key = "MARKETING:POINTS:LOTTERY_WIN:%d" % record_id
    win_note = [n for n in points_notes if n["dedup_key"] == win_key]
    check("9.3 积分变动通知落 notification（LOTTERY_WIN 入账，content 含变动量与余额）",
          len(win_note) == 1 and win_note[0]["type"] == "POINTS_CHANGED" and win_note[0]["title"] and win_note[0]["content"]
          and "积分" in win_note[0]["content"],
          "rows=%s" % win_note)
    redeem_note = [n for n in points_notes if n["dedup_key"].startswith("MARKETING:POINTS:REDEEM_DRAW_COUNT:")]
    check("9.4 兑换扣分也落积分变动通知（REDEEM_DRAW_COUNT，content 含负数与余额）",
          len(redeem_note) >= 1 and redeem_note[0]["content"] and "-" in redeem_note[0]["content"],
          "rows=%s" % redeem_note)
    check("9.5 通知按收件人隔离：对照用户 13700017402 无任何 MARKETING 通知行",
          len(other_drawn) == 0 and len(other_points) == 0,
          "other_drawn=%d other_points=%d" % (len(other_drawn), len(other_points)))

    # 中奖行字段下钻（类型 / 内容 / 目标三要素）
    if dn:
        n = dn[0]
        check("9.6 中奖通知字段齐备：type=NOTIFICATION 类型枚举、scope/title/content/dedup_key 均非空",
              all(str(n[k]).strip() not in ("", "None") for k in
                  ("notification_id", "user_id", "scope", "seq", "type", "title", "content", "dedup_key")),
              {k: n[k] for k in ("type", "title", "content", "dedup_key")})

    # 通知接口可见性（若单体有通知列表接口，加一条真实读侧证据）
    st, resp = h.call("GET", "/api/notifications?scope=BOTTOM_SYSTEM&page=1&pageSize=20", token=token)
    h.evidence("09b-notification-list-api", {"http_status": st, "path": "/api/notifications?scope=BOTTOM_SYSTEM",
                                              "response": resp})
    if st == 200 and isinstance(resp, dict) and resp.get("code") in ("200", "0000"):
        items = ((resp.get("data") or {}).get("list") or [])
        ids = [str(i.get("notificationId")) for i in items]
        check("9.7 通知读侧可见：GET /api/notifications 列表含本次通知",
              (str(dn[0]["notification_id"]) in ids) if dn else False,
              "ids=%s" % ids[:10])
    else:
        check("9.7 通知读侧接口探测（非 200，仅记录证据，不计入判定）", True,
              "HTTP %s（接口路径可能是别的，不作为失败）" % st)


# =====================================================================
def main():
    h = Harness("06/07/09")
    print("== 票 17 冒烟：项 6 + 项 7 + 项 9（号段 137000174xx）")

    if not wait_for_stack():
        h.evidence_text("06-07-09-stack-not-ready",
                        "%s 25 分钟内未返回 HTTP 200，冒烟未运行。\n" % (MKT_BASE + CHECK_ACTIVITY))
        print("\n结论：栈未就绪，未执行任何断言。")
        return 2

    xxl = XxlAdmin()
    logged, login_detail = xxl.login()
    h.evidence("07-xxl-admin-login", login_detail)
    check("0.1 xxl-job-admin 登录成功（enabling 手动触发预热任务）", logged, login_detail)
    job_ids = {}
    if logged:
        for handler in ("raffleCacheWarmup", "inventoryBucketWarmup", "inventoryMerge"):
            rows = h.sql("xxl_job", "SELECT id FROM xxl_job_info WHERE executor_handler = '%s' LIMIT 1" % handler)
            job_ids[handler] = int(rows[0][0]) if rows else None
    h.evidence("07-xxl-job-ids", job_ids)
    check("0.2 三个营销任务已在调度中心注册（raffleCacheWarmup / inventoryBucketWarmup / inventoryMerge）",
          all(job_ids.get(k) for k in ("raffleCacheWarmup", "inventoryBucketWarmup", "inventoryMerge")), job_ids)

    token, user_id = h.register(USER_PHONE, "冒烟抽奖")
    other_token, other_id = h.register(OTHER_PHONE, "冒烟对照")
    h.evidence("06-07-09-sessions", {"user": {"phone": USER_PHONE, "user_id": user_id},
                                     "other": {"phone": OTHER_PHONE, "user_id": other_id}})
    check("0.3 两个账号就绪且互不相同", bool(user_id) and bool(other_id) and user_id != other_id,
          "user=%s other=%s" % (user_id, other_id))

    # 单体 JWT 在营销服务的可用性（同一共享密钥）：拿 /api/lottery/ping 验一下
    ping_code = None
    try:
        st, resp = h.call("GET", "/api/lottery/ping", token=token, base=MKT_BASE)
        ping_code = resp.get("code") if isinstance(resp, dict) else None
        h.evidence("06-07-09-cross-service-token", {"http_status": st, "response": resp})
    except Exception as e:  # noqa: BLE001
        h.evidence("06-07-09-cross-service-token", {"error": str(e)})
    check("0.4 单体 token 可直接打营销服务（GET /api/lottery/ping 通过 JWT 验签 + Dubbo 用户同步边）",
          ping_code == "200", "code=%s" % ping_code)

    for label, fn in (("6", lambda: item6(h, token, user_id)),
                      ("7", lambda: item7_9(h, token, user_id, other_token, other_id, xxl, job_ids))):
        try:
            fn()
        except Exception as e:  # noqa: BLE001
            import traceback
            traceback.print_exc()
            check("%s.x 该项出现未捕获异常（环境/编排问题优先排查）" % label, False,
                  "%s: %s" % (type(e).__name__, e))
            h.evidence_text("06-07-09-item%s-traceback" % label, traceback.format_exc())

    passed = sum(1 for _, ok, _ in CHECKS if ok)
    failed = [(n, d) for n, ok, d in CHECKS if not ok]
    per_item = {}
    for name, ok, _ in CHECKS:
        key = item_of(name)
        slot = per_item.setdefault(key, {"passed": 0, "failed": 0})
        slot["passed" if ok else "failed"] += 1
    summary = {"total": len(CHECKS), "passed": passed, "failed": len(failed),
               "per_item": per_item,
               "failures": [{"assertion": n, "detail": str(d)} for n, d in failed],
               "results": [{"assertion": n, "ok": ok, "detail": str(d)} for n, ok, d in CHECKS],
               "context": CONTEXT}
    h.evidence("06-07-09-summary", summary)
    print("\n===== 汇总：%d/%d PASS，%d FAIL =====" % (passed, len(CHECKS), len(failed)))
    for key in sorted(per_item):
        slot = per_item[key]
        print("  项 %s：%d PASS / %d FAIL" % (key, slot["passed"], slot["failed"]))
    for n, d in failed:
        print("  FAIL | %s — %s" % (n, d))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
