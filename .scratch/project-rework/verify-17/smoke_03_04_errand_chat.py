# -*- coding: utf-8 -*-
"""票 17 冒烟 03+04：跑腿单人单全流程（含超时回调）+ 1v1 私信。

专属号段 137000173xx：
    13700017301 发布方（也兼私信 A 端）
    13700017302 接单方（兼「非会话成员」探针）
    13700017303 私信对家
    13700017399 审核员（提权 ADMIN，走 /api/admin/reviews/decide 真实裁决接口）

跑法：
    cd C:\\Users\\a1368\\Desktop\\better_school
    python .scratch\\project-rework\\verify-17\\smoke_03_04_errand_chat.py

口径说明（详见 evidence/03-errand-full-chain.md / 04-direct-message.md）：
- 先付后审（ADR-0003）：支付只到 PENDING_REVIEW，需管理员裁决通过才 PUBLISHED，之后才可抢单；
  因此「发布→支付→接单→取件→送达→确认」中间夹一步 ADMIN 裁决（同一批真实 HTTP 接口）。
- 超时路径不干等 15 分钟真实窗口：用**中心侧分表回拨 action_time / next_fire_time** 这个 seam 造到期，
  之后仍然由真实的 xxl `timeoutTaskScanJob`（10s 周期）扫到并经 Dubbo 回调单体改判——
  单体侧的 15 分钟兜底扫描（ErrandTimeoutScanJob）在本单上不可能触发（订单 create_time 才刚过 2 分钟），
  这是「改判确实来自超时中心回调」的判别依据。
- 私信发送走 REST `POST /api/chat/rooms/{id}/messages`（与 chat 集成测试同一 seam），不需要 WS 票据。
"""
import http.cookiejar
import json
import os
import re
import sys
import time
import traceback
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from smoke_lib import (  # noqa: E402
    BS_LOG,
    EVIDENCE_DIR,
    TC_BASE,
    TC_LOG,
    Harness,
    log_offset,
    read_since,
    wait_until,
)

PUB_PHONE = "13700017301"
TAKER_PHONE = "13700017302"
PEER_PHONE = "13700017303"
ADMIN_PHONE = "13700017399"

ERRAND_PAY_TIMEOUT = "ERRAND_PAY_TIMEOUT"
CALLBACK_GROUP = "better-school"
SHARDS = ["timeout_task_%02d" % i for i in range(16)]


# ---------------------------------------------------------------- 断言小工具

class Smoke(object):
    def __init__(self, name):
        self.h = Harness(name)
        self.tag = "-"
        self.passed = 0
        self.failed = []

    def section(self, tag):
        self.tag = tag
        print("\n===== %s =====" % tag)

    def check(self, label, ok, detail=None):
        print("%s [%s] %s%s" % ("PASS" if ok else "FAIL", self.tag, label,
                                ("" if detail is None else " — %s" % detail)))
        if ok:
            self.passed += 1
        else:
            self.failed.append("%s / %s" % (self.tag, label))
        return bool(ok)

    def eq(self, label, actual, expected):
        return self.check(label, actual == expected,
                          "actual=%r expected=%r" % (actual, expected))

    def poll(self, label, fn, timeout=90, interval=2.0):
        """轮询直到 fn() 真值；超时记一条 FAIL 并返回 None（不抛，保证后续断言继续跑）。"""
        try:
            value = wait_until(fn, timeout=timeout, interval=interval, what=label)
        except AssertionError:
            self.check(label, False, "轮询 %.0fs 超时" % timeout)
            return None
        self.check(label, True, value)
        return value


def sql1(sm, statement, db="better_school"):
    rows = sm.h.sql(db, statement)
    return rows[0] if rows else None


def one(sm, statement, db="better_school"):
    row = sql1(sm, statement, db)
    return row[0] if row else None


def errand_state(sm, order_id):
    return one(sm, "SELECT state FROM errand_order WHERE errand_order_id=%d" % order_id)


def errand_events(sm, order_id):
    return [tuple(r) for r in sm.h.sql(
        "better_school",
        "SELECT event_type, IFNULL(from_state,'<NULL>'), to_state, IFNULL(actor_id,'<NULL>'), actor_role "
        "FROM errand_order_event WHERE order_id=%d ORDER BY id" % order_id)]


def step_state(sm, order_id, label, expect):
    state = errand_state(sm, order_id)
    sm.eq("%s → errand_order.state" % label, state, expect)
    return state


def trigger_xxl_job(executor_handler):
    """兜底用：经 xxl-job-admin（127.0.0.1:9090，admin/123456）手动触发**同一个 job**。

    只调运维面，不改任何业务数据；返回 (jobId, 响应体) 或 None。
    """
    base = "http://127.0.0.1:9090/xxl-job-admin"
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    login = urllib.parse.urlencode({"userName": "admin", "password": "123456"}).encode()
    req = urllib.request.Request(base + "/auth/doLogin", data=login, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    opener.open(req, timeout=10)
    listing_body = urllib.parse.urlencode({
        "jobGroup": 0, "triggerStatus": -1, "jobDesc": executor_handler,
        "executorHandler": executor_handler, "author": "", "start": 0, "length": 50}).encode()
    req = urllib.request.Request(base + "/jobinfo/pageList", data=listing_body, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    listing = json.loads(opener.open(req, timeout=10).read().decode("utf-8", "replace"))
    job = next((r for r in listing.get("records", [])
                if r.get("executorHandler") == executor_handler), None)
    if job is None:
        return None
    trigger = urllib.parse.urlencode({"id": job["id"], "executorParam": "", "addressList": ""}).encode()
    req = urllib.request.Request(base + "/jobinfo/trigger", data=trigger, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    body = opener.open(req, timeout=10).read().decode("utf-8", "replace")
    return job["id"], body


def errand_body(description, images=None):
    body = {
        "type": "EXPRESS_PICKUP",
        "description": description,
        "pickupLocation": "菜鸟驿站",
        "deliveryLocation": "6号楼楼下",
        "reward": 10,
        "timeLimit": "HOURS_2",
    }
    if images is not None:
        body["images"] = images
    return body


# ---------------------------------------------------------------- 项 3A：全链路

def item_03a_full_chain(sm):
    h = sm.h
    pub_tok, pub_id = h.register(PUB_PHONE, "冒烟跑腿发布者")
    tak_tok, tak_id = h.register(TAKER_PHONE, "冒烟跑腿接单者")
    h.register(ADMIN_PHONE, "冒烟审核员")
    admin_tok, admin_id = h.promote_admin(ADMIN_PHONE)
    sm.check("发布方/接单方/审核员三个账号可用且互不相同",
             len({pub_id, tak_id, admin_id}) == 3,
             "publisher=%s taker=%s admin=%s" % (pub_id, tak_id, admin_id))

    # --- 配图：真上传一张 PNG 作为发布配图 ---
    upload = h.upload_png(pub_tok, "smoke17-errand.png")
    img_url = (upload or {}).get("url") or ""
    sm.check("h.upload_png 上传配图拿到 url", bool(img_url), img_url)

    # --- 发单 ---
    order_id = h.ok("POST", "/api/errand/orders", errand_body("帮忙取个快递（票17冒烟-全链路）", [img_url]),
                    token=pub_tok)
    sm.check("发单返回 orderId", isinstance(order_id, int) and order_id > 0, order_id)
    step_state(sm, order_id, "发单", "PENDING_PAYMENT")
    detail = h.ok("GET", "/api/errand/orders/%d" % order_id, token=pub_tok)
    sm.check("订单详情带出上传的配图", img_url in (detail.get("images") or []),
             detail.get("images"))
    reg = sql1(sm, "SELECT type, state, callback_group, action_time FROM timeout_register_outbox "
                   "WHERE type='%s' AND biz_id='%d'" % (ERRAND_PAY_TIMEOUT, order_id))
    sm.check("发单同事务落超时注册 outbox（PAY_TIMEOUT，回调 group=better-school）",
             bool(reg) and reg[2] == CALLBACK_GROUP, reg)

    # --- 支付（先付后审：PENDING_REVIEW） ---
    h.ok("POST", "/api/errand/orders/%d/pay" % order_id, None, token=pub_tok)
    step_state(sm, order_id, "支付", "PENDING_REVIEW")

    # --- 管理员裁决通过（先付后审的必需一步，真实 HTTP 裁决接口） ---
    h.ok("POST", "/api/admin/reviews/decide",
         {"targetType": "ERRAND", "targetId": order_id, "approve": True}, token=admin_tok)
    step_state(sm, order_id, "审核通过", "PUBLISHED")
    public = h.ok("GET", "/api/errand/orders?page=1&pageSize=50&status=PUBLISHED")
    ids = [int(o["orderId"]) for o in (public.get("list") or [])]
    sm.check("通过后订单出现在公开列表（可被抢）", order_id in ids, "列表含 %d 单" % len(ids))

    # --- 抢单 → 取件 → 送达 → 确认 ---
    h.ok("POST", "/api/errand/orders/%d/grab" % order_id, None, token=tak_tok)
    step_state(sm, order_id, "接单", "TAKEN")
    h.ok("POST", "/api/errand/orders/%d/pickup" % order_id, None, token=tak_tok)
    step_state(sm, order_id, "取件", "PICKED_UP")
    h.ok("POST", "/api/errand/orders/%d/deliver" % order_id, None, token=tak_tok)
    step_state(sm, order_id, "送达", "DELIVERED")
    h.ok("POST", "/api/errand/orders/%d/confirm" % order_id, None, token=pub_tok)
    step_state(sm, order_id, "确认完成", "COMPLETED")

    final = h.ok("GET", "/api/errand/orders/%d" % order_id)
    sm.eq("HTTP 详情最终状态 COMPLETED", final.get("status"), "COMPLETED")
    sm.eq("详情接单方 = 接单者", int(final.get("takerId")), tak_id)

    # --- 事件流水：每次流转恰一行，顺序/前后态/操作方完全一致 ---
    expected = [
        ("CREATED", "<NULL>", "PENDING_PAYMENT", str(pub_id), "PUBLISHER"),
        ("PAID", "PENDING_PAYMENT", "PENDING_REVIEW", str(pub_id), "PUBLISHER"),
        ("REVIEW_APPROVED", "PENDING_REVIEW", "PUBLISHED", str(admin_id), "ADMIN"),
        ("TAKEN", "PUBLISHED", "TAKEN", str(tak_id), "TAKER"),
        ("PICKED_UP", "TAKEN", "PICKED_UP", str(tak_id), "TAKER"),
        ("DELIVERED", "PICKED_UP", "DELIVERED", str(tak_id), "TAKER"),
        ("CONFIRMED", "DELIVERED", "COMPLETED", str(pub_id), "PUBLISHER"),
    ]
    actual = errand_events(sm, order_id)
    sm.eq("errand_order_event 一次流转一行、顺序与前后态/操作方完全一致", actual, expected)
    creation = sm.h.sql("better_school",
                        "SELECT event_type, IFNULL(from_state,'<NULL>') FROM errand_order_event "
                        "WHERE order_id=%d AND from_state IS NULL" % order_id)
    sm.eq("from_state IS NULL 的创建行恰一行且为 CREATED",
          [tuple(r) for r in creation], [("CREATED", "<NULL>")])
    sm.eq("事件总行数 = 流转次数 7", len(actual), 7)

    h.evidence("03a-errand-full-chain", {
        "publisherId": pub_id, "takerId": tak_id, "adminId": admin_id,
        "orderId": order_id, "imageUrl": img_url,
        "stateTrajectory": ["PENDING_PAYMENT", "PENDING_REVIEW", "PUBLISHED",
                            "TAKEN", "PICKED_UP", "DELIVERED", "COMPLETED"],
        "errandOrderRow": sm.h.sql("better_school",
            "SELECT errand_order_id, publisher_id, taker_id, state, reward, images "
            "FROM errand_order WHERE errand_order_id=%d" % order_id),
        "errandOrderEventRows": actual,
        "registerOutboxRow": reg,
        "orderDetailResponse": final,
    })


# ---------------------------------------------------------------- 项 3B：超时回调

def item_03b_timeout(sm):
    h = sm.h
    pub_tok, pub_id = h.register(PUB_PHONE, "冒烟跑腿发布者")
    offset_bs = log_offset(BS_LOG)
    offset_tc = log_offset(TC_LOG)

    order_id = h.ok("POST", "/api/errand/orders",
                    errand_body("超时未支付单（票17冒烟-超时回调）"), token=pub_tok)
    sm.check("超时单创建返回 orderId", isinstance(order_id, int) and order_id > 0, order_id)
    step_state(sm, order_id, "超时单发单后", "PENDING_PAYMENT")

    # 1) 注册边：业务库 outbox 即时发送置 SENT（Dubbo 注册成功才置 SENT）
    def reg_row():
        rows = h.sql("better_school", "SELECT state, retry_count, callback_group, action_time "
                                      "FROM timeout_register_outbox WHERE type='%s' AND biz_id='%d'"
                                      % (ERRAND_PAY_TIMEOUT, order_id))
        return rows[0] if rows and rows[0][0] == "SENT" else None

    reg = sm.poll("创建订单后超时注册 outbox 被即时发送（state=SENT，回调 group=better-school）",
                  reg_row, timeout=90)

    # 2) 中心侧落地：16 张分表任一，绝不假设 timeout_task_00
    def find_task():
        for table in SHARDS:
            rows = h.sql("bs_timeout",
                         "SELECT status, action_time, next_fire_time, retry_count, callback_group "
                         "FROM %s WHERE task_type='%s' AND biz_id='%d'" % (table, ERRAND_PAY_TIMEOUT, order_id))
            if rows:
                return [table] + rows[0]
        return None

    before = sm.poll("中心侧 timeout_task_*（16 张分表任一张）注册到该订单", find_task, timeout=90)
    if not before:
        return
    table = before[0]
    sm.check("中心侧任务初始为未终态 WAITING/RETRYING",
             before[1] in ("WAITING", "RETRYING"), before[1])
    sm.eq("中心侧任务回调 group = better-school", before[5], CALLBACK_GROUP)
    future = one(sm, "SELECT (next_fire_time > NOW()) FROM %s WHERE task_type='%s' AND biz_id='%d'"
                     % (table, ERRAND_PAY_TIMEOUT, order_id), db="bs_timeout")
    sm.eq("中心侧任务到期时刻仍在未来（真实兜底窗口 15 分钟，本步不可能自然到期）", future, "1")

    # 3) 造到期：回拨中心侧分表的 action_time / next_fire_time（不等待真实 15 分钟窗口）
    h.sql("bs_timeout",
          "UPDATE %s SET action_time = DATE_SUB(NOW(), INTERVAL 2 MINUTE), "
          "next_fire_time = DATE_SUB(NOW(), INTERVAL 2 MINUTE), updated_at = NOW() "
          "WHERE task_type='%s' AND biz_id='%d'" % (table, ERRAND_PAY_TIMEOUT, order_id))
    forced = find_task()
    sm.check("已把中心侧任务 action_time/next_fire_time 回拨到 now-2min（造到期 seam）",
             bool(forced) and forced[0] == table, forced)

    # 4) 真 xxl 扫描（timeoutTaskScanJob，10s 周期）扫到 → Dubbo 回调单体 → 关单
    def cancelled():
        return errand_state(sm, order_id) == "CANCELLED"

    mechanism = "xxl timeoutTaskScanJob 自然 10s 周期扫描（中心 → Dubbo 回调单体）"
    fired = False
    try:
        wait_until(cancelled, timeout=60, interval=2, what="自然扫描")
        fired = True
    except AssertionError:
        pass
    if not fired:
        # 兜底：自然周期没生效时，用 xxl-job-admin 运维接口手动触发**同一个 job**（不改业务侧任何东西）
        trig = trigger_xxl_job("timeoutTaskScanJob")
        mechanism = ("xxl timeoutTaskScanJob 经 xxl-job-admin /jobinfo/trigger 手动触发"
                     "（自然 10s 周期 60s 内未生效）")
        sm.check("兜底：经 xxl-job-admin 手动触发同一扫描 job", bool(trig), trig)
        try:
            wait_until(cancelled, timeout=90, interval=2, what="手动触发扫描")
        except AssertionError:
            pass
    sm.check("超时中心扫到该任务并经 Dubbo 回调把订单改判为 CANCELLED", cancelled(), mechanism)

    def task_success():
        rows = h.sql("bs_timeout", "SELECT status, retry_count, updated_at FROM %s "
                                   "WHERE task_type='%s' AND biz_id='%d'"
                                   % (table, ERRAND_PAY_TIMEOUT, order_id))
        return rows[0] if rows and rows[0][0] == "SUCCESS" else None

    task_terminal = sm.poll("中心侧任务置终态 SUCCESS（回调被单体成功回报）", task_success, timeout=60)

    # 5) 事件流水与判别依据
    sm.eq("超时关单事件 PAY_TIMEOUT：PENDING_PAYMENT→CANCELLED，操作方 SYSTEM，actor_id 为空",
          errand_events(sm, order_id),
          [("CREATED", "<NULL>", "PENDING_PAYMENT", str(pub_id), "PUBLISHER"),
           ("PAY_TIMEOUT", "PENDING_PAYMENT", "CANCELLED", "<NULL>", "SYSTEM")])
    age = one(sm, "SELECT TIMESTAMPDIFF(MINUTE, create_time, NOW()) FROM errand_order "
                  "WHERE errand_order_id=%d" % order_id)
    sm.check("订单 age < 15 分钟：改判不可能来自单体自身 15 分钟兜底扫描（只能是中心回调）",
             age is not None and int(age) < 15, "create_time 距 now %s 分钟" % age)

    # 6) 单体日志证据：回调确实打到 ErrandPayTimeout 分支
    bs_tail = read_since(BS_LOG, offset_bs)
    hit = [ln for ln in bs_tail.splitlines()
           if "[ErrandPayTimeout]" in ln and str(order_id) in ln]
    sm.check("单体日志出现 [ErrandPayTimeout] 关单记录（回调落到业务侧）", bool(hit),
             (hit[-1][:160] if hit else "无匹配行"))

    # 7) 中心日志（拿不到也算证据缺失而非失败）与死信面
    tc_tail = read_since(TC_LOG, offset_tc)
    # 过滤口径：本次回调相关的行 = 中心为该任务动态建回调引用的行（ITimeoutTaskCallback / better-school group），
    # 或行内以独立词出现 bizId（\b 边界，避免 id=2 命中 "2000"/"2.0.2" 这类假阳性）
    tc_hits = [ln for ln in tc_tail.splitlines()
               if "ITimeoutTaskCallback" in ln or re.search(r"\b%d\b" % order_id, ln)][-20:]
    st, dead = h.call("GET", "/api/admin/timeout-tasks/dead-letters?page=1&pageSize=10", base=TC_BASE)
    dead_list = (dead.get("data") or {}).get("list") if isinstance(dead, dict) else None
    sm.check("中心死信管理面可达（HTTP 200）", st == 200, "HTTP %s" % st)
    sm.check("本任务未进死信（FAILED）", bool(dead_list is not None) and
             all(not (d.get("taskType") == ERRAND_PAY_TIMEOUT and d.get("bizId") == str(order_id))
                 for d in dead_list), dead_list)

    h.evidence("03b-timeout-task", {
        "orderId": order_id,
        "mechanism": mechanism,
        "seam": "回拨中心侧分表行的 action_time / next_fire_time 到 now-2min（不干等 15 分钟真实窗口）",
        "shardTable": table,
        "taskBefore": before,
        "taskForced": forced,
        "taskTerminal": task_terminal,
        "registerOutbox": reg,
        "errandOrderRow": h.sql("better_school",
            "SELECT errand_order_id, state, create_time, update_time FROM errand_order "
            "WHERE errand_order_id=%d" % order_id),
        "errandOrderEventRows": errand_events(sm, order_id),
        "deadLettersFirstPage": dead,
    })
    h.evidence_text("03b-bs-log-errand-pay-timeout", "\n".join(hit[-10:]))
    if tc_hits:
        h.evidence_text("03b-tc-log-lines", "\n".join(tc_hits))


# ---------------------------------------------------------------- 项 4：1v1 私信

def item_04_chat(sm):
    h = sm.h
    # 段自足：即使 03A 失败也要能独立跑（register 对已注册号自动回落登录）
    a_tok, a_id = h.register(PUB_PHONE, "冒烟跑腿发布者")
    b_tok, b_id = h.register(PEER_PHONE, "冒烟私信对家")
    out_tok, out_id = h.register(TAKER_PHONE, "冒烟跑腿接单者")  # 非会话成员探针

    room = h.ok("POST", "/api/chat/rooms/direct", {"targetUserId": b_id}, token=a_tok)
    room_id = int(room["roomId"])
    sm.check("建 1v1 会话返回 roomId", room_id > 0, room_id)

    pair = "user_min=%d AND user_max=%d" % (min(a_id, b_id), max(a_id, b_id))
    sm.eq("同一对人恒一条 room 行（uk_direct_pair）",
          int(h.one("better_school", "SELECT COUNT(*) FROM room WHERE %s" % pair)), 1)
    sm.eq("room_id 在 room 表唯一落一行",
          int(h.one("better_school", "SELECT COUNT(*) FROM room WHERE room_id=%d" % room_id)), 1)

    # 反向再建一次：幂等回同一会话，成员不重复
    again = h.ok("POST", "/api/chat/rooms/direct", {"targetUserId": a_id}, token=b_tok)
    sm.eq("对家反向建会话幂等回同一 roomId", int(again["roomId"]), room_id)
    sm.eq("反向重建后 room 行数仍为 1",
          int(h.one("better_school", "SELECT COUNT(*) FROM room WHERE %s" % pair)), 1)

    members = [int(r[0]) for r in h.sql("better_school",
                                        "SELECT user_id FROM room_member WHERE room_id=%d ORDER BY user_id" % room_id)]
    sm.eq("room_member 恰两行且就是双方", members, sorted([a_id, b_id]))

    # --- 双向发消息（REST，与 chat 集成测试同一 seam，不需要 WS 票据） ---
    tag = str(int(time.time()))
    cm1, cm2 = "smoke17-03-%s-a" % tag, "smoke17-03-%s-b" % tag
    c1, c2 = "冒烟私信 A→B（%s）" % tag, "冒烟私信 B→A（%s）" % tag
    m1 = h.ok("POST", "/api/chat/rooms/%d/messages" % room_id,
              {"clientMsgId": cm1, "msgType": "TEXT", "content": c1}, token=a_tok)
    m2 = h.ok("POST", "/api/chat/rooms/%d/messages" % room_id,
              {"clientMsgId": cm2, "msgType": "TEXT", "content": c2}, token=b_tok)
    sm.eq("A→B 消息 seq 从 room.last_seq 递增", int(m2["seq"]) - int(m1["seq"]), 1)
    sm.eq("A→B 消息 fromUserId 为 A", int(m1["fromUserId"]), a_id)
    sm.eq("B→A 消息 fromUserId 为 B", int(m2["fromUserId"]), b_id)

    db_msgs = h.sql("better_school",
                    "SELECT seq, from_user_id, content, state, client_msg_id FROM message "
                    "WHERE room_id=%d AND client_msg_id IN ('%s','%s') ORDER BY seq" % (room_id, cm1, cm2))
    sm.eq("两条消息在 message 表持久化（发送方/内容/状态 NORMAL）",
          [(int(r[0]), int(r[1]), r[2], r[3]) for r in db_msgs],
          [(int(m1["seq"]), a_id, c1, "NORMAL"), (int(m2["seq"]), b_id, c2, "NORMAL")])
    sm.eq("room 列表冗余列 = 最后一条消息（类型/预览）",
          [h.one("better_school", "SELECT last_msg_type FROM room WHERE room_id=%d" % room_id),
           h.one("better_school", "SELECT last_msg_preview FROM room WHERE room_id=%d" % room_id)],
          ["TEXT", c2])

    # --- 对家回读 ---
    history = h.ok("GET", "/api/chat/rooms/%d/messages" % room_id, token=b_tok)
    by_seq = {int(m["seq"]): m for m in history}
    sm.check("B 通过 API 历史接口读到 A 的消息（seq/内容/发送方齐备）",
             by_seq.get(int(m1["seq"]), {}).get("content") == c1
             and int(by_seq.get(int(m1["seq"]), {}).get("fromUserId", -1)) == a_id,
             by_seq.get(int(m1["seq"])))
    sm.check("B 通过同一接口读到自己发的消息", by_seq.get(int(m2["seq"]), {}).get("content") == c2,
             by_seq.get(int(m2["seq"])))
    members_api = h.ok("GET", "/api/chat/rooms/%d/members" % room_id, token=a_tok)
    sm.eq("成员接口 memberCount=2", int(members_api["memberCount"]), 2)

    # --- 时间线（写扩散收件箱） ---
    for seq in (int(m1["seq"]), int(m2["seq"])):
        sm.eq("timeline 对 seq=%d 双方各一行" % seq,
              int(h.one("better_school", "SELECT COUNT(*) FROM timeline WHERE room_id=%d AND seq=%d"
                        % (room_id, seq))), 2)
    # B 的投递序号游标：从「这条消息之前的游标」增量补拉（避免历史消息把 limit 占满的假阴性）
    b_delivery_1 = int(h.one("better_school",
                             "SELECT delivery_seq FROM timeline WHERE room_id=%d AND user_id=%d AND seq=%d"
                             % (room_id, b_id, int(m1["seq"]))))
    tl = h.ok("GET", "/api/chat/timeline?afterDeliverySeq=%d" % (b_delivery_1 - 1), token=b_tok)
    tl_seqs = {int(x["seq"]) for x in tl if x.get("roomId") is not None and int(x["roomId"]) == room_id}
    sm.check("B 增量补拉接口 /api/chat/timeline（afterDeliverySeq=游标-1）含这两条",
             {int(m1["seq"]), int(m2["seq"])} <= tl_seqs, sorted(tl_seqs))

    # --- 已读水位线推进 ---
    last_seq = int(h.one("better_school", "SELECT last_seq FROM room WHERE room_id=%d" % room_id))
    read_before = int(h.one("better_school", "SELECT last_read_seq FROM room_member WHERE room_id=%d AND user_id=%d"
                            % (room_id, b_id)))

    def unread_of(token):
        data = h.ok("GET", "/api/chat/rooms", token=token)
        for item in (data.get("list") or []):
            if int(item["roomId"]) == room_id:
                return int(item["unread"])
        return None

    sm.check("推水位线前 B 存在未读（last_read_seq < room.last_seq）",
             read_before < last_seq, "last_read_seq=%d last_seq=%d" % (read_before, last_seq))
    sm.eq("会话列表未读数 = last_seq − 水位线", unread_of(b_tok), last_seq - read_before)

    new_read = h.ok("POST", "/api/chat/rooms/%d/read" % room_id, None, token=b_tok)
    sm.eq("/read 返回推进后的水位线 = room.last_seq", int(new_read), last_seq)
    read_after = int(h.one("better_school", "SELECT last_read_seq FROM room_member WHERE room_id=%d AND user_id=%d"
                           % (room_id, b_id)))
    sm.eq("room_member.last_read_seq 落到 last_seq", read_after, last_seq)
    sm.eq("推进后 B 的会话未读归零", unread_of(b_tok), 0)

    # --- 非成员不可见（证明成员恰两人） ---
    st, r = h.call("GET", "/api/chat/rooms/%d/messages" % room_id, token=out_tok)
    code = r.get("code") if isinstance(r, dict) else None
    sm.eq("非成员（接单者）拉该会话历史被拒 403", code, "403")

    # --- WS 票据握手可用（发送本身不需要它） ---
    ticket = h.ok("POST", "/api/chat/ws-ticket", None, token=a_tok)
    sm.check("WS 票据握手端点可用（本次发送走 REST，未使用票据）",
             bool((ticket or {}).get("ticket")), (ticket or {}).get("ticket", "")[:16])

    h.evidence("04-direct-message", {
        "userA": a_id, "userB": b_id, "outsider": out_id, "roomId": room_id,
        "roomRow": h.sql("better_school",
                         "SELECT room_id, user_min, user_max, last_seq, last_msg_type, last_msg_preview, state "
                         "FROM room WHERE room_id=%d" % room_id),
        "roomMemberRows": h.sql("better_school",
                                "SELECT user_id, last_read_seq, hidden_at FROM room_member "
                                "WHERE room_id=%d ORDER BY user_id" % room_id),
        "messageRows": h.sql("better_school",
                             "SELECT msg_id, seq, from_user_id, msg_type, content, state, client_msg_id "
                             "FROM message WHERE room_id=%d AND seq IN (%d,%d) ORDER BY seq"
                             % (room_id, int(m1["seq"]), int(m2["seq"]))),
        "timelineRows": h.sql("better_school",
                              "SELECT user_id, seq, msg_id, delivery_seq FROM timeline "
                              "WHERE room_id=%d AND seq IN (%d,%d) ORDER BY seq, user_id"
                              % (room_id, int(m1["seq"]), int(m2["seq"]))),
        "readState": {"lastSeq": last_seq, "lastReadSeqBefore": read_before,
                      "lastReadSeqAfter": read_after, "readApiReturn": int(new_read)},
        "sendResponseA": m1, "sendResponseB": m2,
        "historySeqsReturned": sorted(by_seq)[-6:],
        "nonMemberReadCode": code,
    })


# ---------------------------------------------------------------- 主流程

def guard(sm, label, fn):
    try:
        fn()
    except Exception as e:  # noqa: BLE001
        sm.check("%s 段执行中断" % label, False, "%s: %s" % (type(e).__name__, str(e)[:300]))
        print("    (最后一帧) %s" % traceback.format_exc().strip().splitlines()[-1][:200])


def main():
    sm = Smoke("smoke_03_04")
    t0 = time.time()
    sm.section("03A 跑腿单人单全链路")
    guard(sm, "03A", lambda: item_03a_full_chain(sm))
    sm.section("03B 跑腿超时回调（中心扫描 → Dubbo 回调 → 关单）")
    guard(sm, "03B", lambda: item_03b_timeout(sm))
    sm.section("04 1v1 私信")
    guard(sm, "04", lambda: item_04_chat(sm))

    summary = {
        "passed": sm.passed,
        "failed": sm.failed,
        "seconds": round(time.time() - t0, 1),
    }
    sm.h.evidence("03-04-summary", summary)
    print("\n===== 汇总 =====")
    print("PASS 断言 %d 条，FAIL %d 条，用时 %.0fs" % (sm.passed, len(sm.failed), summary["seconds"]))
    for f in sm.failed:
        print("  FAIL: %s" % f)
    print("证据目录：%s" % EVIDENCE_DIR)
    return 1 if sm.failed else 0


if __name__ == "__main__":
    sys.exit(main())
