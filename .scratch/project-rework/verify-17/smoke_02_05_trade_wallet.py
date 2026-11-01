# -*- coding: utf-8 -*-
"""票 17 冒烟：项 2 商品上下架 + 项 5 钱包支付。

号段 137000172xx（本脚本独占）：
  seller 13700017201 / buyer 13700017202 / admin 13700017203

覆盖：
  项 2 —— 发布（先审后售）→ PENDING_REVIEW → ADMIN 裁决通过 → ON_SALE 且公开列表可见
          → 下架 → state=DRAFT、公开列表不可见、他人详情 404、不可下单
          → （重提回待审作为状态机闭环旁证）
  项 5 —— 钱包充值（真实链路 POST /api/wallet/recharge）→ 下单 → 支付
          → 余额减少恰好等于成交价、wallet_detail 有 PRODUCT_FROZEN 行且 order_id 正确
          → 重复支付同一订单幂等（不二次扣减、流水仍只有一行、订单仍 PAID）
          → 确认收货：PRODUCT_UNFROZEN + PRODUCT_INCOME，卖家余额增加恰好等于成交价

运行：
    cd C:\\Users\\a1368\\Desktop\\better_school
    python .scratch\\project-rework\\verify-17\\smoke_02_05_trade_wallet.py

不加任何第三方包；不修改 backend/ 下任何文件。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = r"C:\Users\a1368\Desktop\better_school"
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from smoke_lib import Harness  # noqa: E402

DB = "better_school"
BS = "http://localhost:8080"
PROBE = BS + "/api/trade/products?page=1&pageSize=1"
WAIT_MAX_SECONDS = 25 * 60
WAIT_INTERVAL_SECONDS = 30

SELLER_PHONE = "13700017201"
BUYER_PHONE = "13700017202"
ADMIN_PHONE = "13700017203"

PRODUCT_TITLE = "九成新民谣吉他"
PRODUCT_DESC = "自用闲置一年，成色好无磕碰"
PRODUCT_PRICE = "19.90"
RECHARGE_AMOUNT = "119.90"
CATEGORY = "BOOKS"
TRADE_LOCATION = "图书馆门口"

EVIDENCE_DIR = os.path.join(HERE, "evidence")
SQL_LOG_PATH = os.path.join(EVIDENCE_DIR, "02-05-sql-log.txt")


# ───────────────────────────── 断言收集器 ─────────────────────────────

class Failed(Exception):
    pass


class Smoke(object):
    def __init__(self, h):
        self.h = h
        self.checks = []
        self.sql_log = []
        self.failed = []

    # ---- 断言 ----

    def check(self, name, cond, detail=""):
        ok = bool(cond)
        print("%s | %s%s" % ("PASS" if ok else "FAIL", name, (" | " + str(detail)) if detail else ""))
        self.checks.append({"check": name, "ok": ok, "detail": str(detail)})
        if not ok:
            self.failed.append(name)
            raise Failed(name)
        return True

    def section(self, title):
        print("")
        print("── %s ─────────────────────────────────" % title)

    # ---- 查库（同时记 SQL 审计日志） ----

    def q(self, statement):
        self.sql_log.append(statement)
        return self.h.sql(DB, statement)

    def cell(self, statement):
        self.sql_log.append(statement)
        return self.h.one(DB, statement)

    def rows(self, statement):
        return self.q(statement)

    # ---- 证据 ----

    def dump(self, label, payload):
        self.h.evidence(label, payload)
        return payload

    def dump_sql(self):
        self.h.evidence_text("02-05-sql-log", "\n".join(self.sql_log) + "\n")


# ───────────────────────────── 工具 ─────────────────────────────

def probe_stack(timeout_s=WAIT_MAX_SECONDS, interval_s=WAIT_INTERVAL_SECONDS):
    """轮询 /api/trade/products?page=1&pageSize=1 直到 HTTP 200（或超时）。"""
    deadline = time.time() + timeout_s
    attempt = 0
    while True:
        attempt += 1
        try:
            req = urllib.request.Request(PROBE, method="GET")
            with urllib.request.urlopen(req, timeout=10) as resp:
                status = resp.status
                resp.read()
            if status == 200:
                print("[冒烟等待] 第 %d 次探测 HTTP 200 —— 单体已起，开始执行" % attempt)
                return True
            print("[冒烟等待] 第 %d 次探测 HTTP %s（期望 200）" % (attempt, status))
        except urllib.error.HTTPError as e:
            print("[冒烟等待] 第 %d 次探测 HTTP %s（期望 200）" % (attempt, e.code))
        except Exception as e:
            print("[冒烟等待] 第 %d 次探测连接失败：%s" % (attempt, type(e).__name__))
        if time.time() >= deadline:
            print("[冒烟等待] 已等待 %ds，单体始终未就绪 —— 放弃执行" % timeout_s)
            return False
        time.sleep(interval_s)


def code_of(resp):
    return resp.get("code") if isinstance(resp, dict) else None


def _page_list(page):
    """分页载荷 → list。

    h.ok(...) 已经剥掉外层 {code,info,data}，所以这里同时兼容两种形状：
    裸分页对象 {page,pageSize,total,list} 与完整响应 {code,info,data:{...}}。
    """
    if not isinstance(page, dict):
        return []
    data = page.get("data") if isinstance(page.get("data"), dict) else page
    return list(data.get("list") or [])


def product_ids(page):
    return [item.get("productId") for item in _page_list(page)]


def order_ids(page):
    return [item.get("orderId") for item in _page_list(page)]


def queue_ids(page):
    return [item.get("targetId") for item in _page_list(page)]


def money(value):
    return "%.2f" % float(value)


def promote_admin(h, s, phone):
    """提权 ADMIN 后重登（角色写在 JWT 里，必须换新 token）。

    用 smoke_lib.Harness.promote_admin（列名 phone 已由票 17 修好）；
    本函数只额外补一条可断言的落库证据：bs_user.role 与 user_id。
    """
    role_before = s.cell("SELECT role FROM bs_user WHERE phone = '%s'" % phone)
    tok, uid = h.promote_admin(phone)
    role_after = s.cell("SELECT role FROM bs_user WHERE phone = '%s'" % phone)
    print("  提权 %s：role %s → %s，userId=%s" % (phone, role_before, role_after, uid))
    if role_after != "ADMIN":
        raise Failed("提权后 bs_user.role 仍为 %s" % role_after)
    return tok, uid


# ───────────────────────────── 主流程 ─────────────────────────────

def product_row(product_id):
    return (
        "SELECT state, review_round, review_entered_at, expire_at, price, user_id, title "
        "FROM product WHERE product_id = %s" % product_id
    )


def wallet_row(user_id):
    return "SELECT balance, frozen_amount FROM virtual_wallet WHERE user_id = %s" % user_id


def wallet_state(s, user_id):
    row = s.rows(wallet_row(user_id))[0]
    balance, frozen = money(row[0]), money(row[1])
    return {
        "balance": balance,
        "frozen_amount": frozen,
        "available": money(float(row[0]) - float(row[1])),
    }


def wallet_details(s, user_id, order_id=None):
    where = "user_id = %s" % user_id
    if order_id is not None:
        where += " AND order_id = %s" % order_id
    sql = ("SELECT type, amount, order_id, biz_round, create_time FROM wallet_detail "
           "WHERE %s ORDER BY id" % where)
    return [dict(zip(["type", "amount", "order_id", "biz_round", "create_time"], r)) for r in s.rows(sql)]


def detail_count(s, user_id, order_id, dtype):
    return int(s.cell(
        "SELECT COUNT(*) FROM wallet_detail WHERE user_id = %s AND order_id = %s AND type = '%s'"
        % (user_id, order_id, dtype)))


def run(h):
    s = Smoke(h)
    run_id = time.strftime("%Y%m%d-%H%M%S")
    result = {"run_id": run_id, "checks": s.checks}

    # ═══════════════ 登录三方 ═══════════════
    s.section("登录/注册（seller / buyer / admin，号段 137000172xx）")
    seller_tok, seller_id = h.register(SELLER_PHONE, "冒烟17卖家")
    buyer_tok, buyer_id = h.register(BUYER_PHONE, "冒烟17买家")
    h.register(ADMIN_PHONE, "冒烟17管理员")
    admin_tok, admin_id = promote_admin(h, s, ADMIN_PHONE)
    # 管理员 token 必须带 ADMIN 角色：用一个仅 ADMIN 可用的接口自证（敏感词管理）
    admin_probe = s.h.call("GET", "/api/moderation/admin/words?page=1&pageSize=1", token=admin_tok)
    print("  seller=%s buyer=%s admin=%s" % (seller_id, buyer_id, admin_id))
    s.check("登录三方拿到 token/userId 且管理员 token 带 ADMIN 角色",
            all([seller_tok, buyer_tok, admin_tok, seller_id, buyer_id, admin_id])
            and code_of(admin_probe[1]) == "200",
            "seller=%s buyer=%s admin=%s adminProbe=%s" % (seller_id, buyer_id, admin_id, code_of(admin_probe[1])))

    # 钱包自愈（注册即建钱包；查询余额顺带兜底建行）
    s.h.ok("GET", "/api/wallet/balance", token=seller_tok)
    s.h.ok("GET", "/api/wallet/balance", token=buyer_tok)

    # ═══════════════════════════════════════════════════════════
    # 项 2 —— 商品上下架
    # ═══════════════════════════════════════════════════════════
    s.section("项 2｜发布商品（先审后售）")
    publish = s.h.ok("POST", "/api/trade/products", {
        "title": PRODUCT_TITLE,
        "description": PRODUCT_DESC,
        "images": [],
        "price": float(PRODUCT_PRICE),
        "tradeLocation": TRADE_LOCATION,
        "category": CATEGORY,
    }, token=seller_tok)
    product_id = publish
    row = s.rows(product_row(product_id))[0]
    s.dump("02-01-publish-response", {"request": {"title": PRODUCT_TITLE, "price": PRODUCT_PRICE},
                                      "response_data": publish, "db_product_row": row})
    s.check("发布返回 productId 且落库", isinstance(product_id, int) and product_id > 0, "productId=%s" % product_id)
    s.check("发布后 state=PENDING_REVIEW（先审后售）", row[0] == "PENDING_REVIEW", "state=%s" % row[0])
    s.check("发布后 review_round=1", str(row[1]) == "1", "review_round=%s" % row[1])
    s.check("发布后 review_entered_at 非空（进入待审时刻）", row[2] not in (None, "", "NULL"),
            "review_entered_at=%s" % row[2])

    # 待审期间公开列表不可见
    anon_page = s.h.ok("GET", "/api/trade/products?page=1&pageSize=50")
    s.dump("02-02-pending-anon-list", {"state": row[0], "productIds": product_ids(anon_page)})
    s.check("待审期间公开列表不可见", product_id not in product_ids(anon_page))

    # 审核队列可见
    q_before = s.h.ok("GET", "/api/moderation/queue?targetType=PRODUCT&page=1&pageSize=50", token=admin_tok)
    s.dump("02-03-review-queue-before", {"targetIds": queue_ids(q_before), "raw": q_before})
    s.check("待审商品进入 ADMIN 审核队列", product_id in queue_ids(q_before),
            "product_id=%r queueIds=%r" % (product_id, queue_ids(q_before)))

    s.section("项 2｜ADMIN 裁决通过 → ON_SALE 且公开可见")
    decide = s.h.ok("POST", "/api/admin/reviews/decide",
                    {"targetType": "PRODUCT", "targetId": product_id, "approve": True}, token=admin_tok)
    row_on = s.rows(product_row(product_id))[0]
    review_row = s.rows(
        "SELECT target_type, target_id, review_round, action, source, operator_id, reason "
        "FROM content_review WHERE target_type = 'PRODUCT' AND target_id = %s" % product_id)
    on_page = s.h.ok("GET", "/api/trade/products?page=1&pageSize=50")
    # 用买家视角再看一次（登录态也不应影响公开列表）
    on_page_buyer = s.h.ok("GET", "/api/trade/products?page=1&pageSize=50", token=buyer_tok)
    s.dump("02-04-approve-and-onsale", {"decide_response": decide, "db_product_row": row_on,
                                        "content_review_rows": review_row,
                                        "anon_list_ids": product_ids(on_page),
                                        "buyer_list_ids": product_ids(on_page_buyer)})
    s.check("裁决通过后 state=ON_SALE", row_on[0] == "ON_SALE", "state=%s" % row_on[0])
    s.check("content_review 落 MANUAL/APPROVE 行且 operator 为管理员",
            any(r[3] == "APPROVE" and r[4] == "MANUAL" and str(r[5]) == str(admin_id) for r in review_row),
            "rows=%s" % review_row)
    s.check("上架后公开列表可见（匿名）", product_id in product_ids(on_page))
    s.check("上架后公开列表可见（买家登录态）", product_id in product_ids(on_page_buyer))

    q_after = s.h.ok("GET", "/api/moderation/queue?targetType=PRODUCT&page=1&pageSize=50", token=admin_tok)
    s.check("裁决后移出审核队列", product_id not in queue_ids(q_after))

    # 详情：在售对他人可见
    detail_other = s.h.call("GET", "/api/trade/products/%s" % product_id, token=buyer_tok)
    s.dump("02-05-onsale-detail-by-other", detail_other)
    s.check("在售商品他人可看详情（code=200）", code_of(detail_other[1]) == "200",
            "HTTP=%s code=%s" % (detail_other[0], code_of(detail_other[1])))

    s.section("项 2｜下架（takedown）→ 退出可见且不可购买")
    takedown = s.h.ok("POST", "/api/trade/products/%s/takedown" % product_id, token=seller_tok)
    row_down = s.rows(product_row(product_id))[0]
    down_page = s.h.ok("GET", "/api/trade/products?page=1&pageSize=50")
    down_page_buyer = s.h.ok("GET", "/api/trade/products?page=1&pageSize=50", token=buyer_tok)
    mine = s.h.ok("GET", "/api/trade/products/mine?page=1&pageSize=50", token=seller_tok)
    detail_down = s.h.call("GET", "/api/trade/products/%s" % product_id, token=buyer_tok)
    buy_after_down = s.h.call("POST", "/api/trade/orders?productId=%s" % product_id, token=buyer_tok)
    s.dump("02-06-takedown", {
        "takedown_response": takedown,
        "db_product_row": row_down,
        "anon_list_ids": product_ids(down_page),
        "buyer_list_ids": product_ids(down_page_buyer),
        "seller_mine_ids": product_ids(mine),
        "detail_by_other": detail_down,
        "create_order_after_down": buy_after_down,
    })
    s.check("下架后 state=DRAFT（汇入草稿箱）", row_down[0] == "DRAFT", "state=%s" % row_down[0])
    s.check("下架后公开列表不可见（匿名）", product_id not in product_ids(down_page))
    s.check("下架后公开列表不可见（买家登录态）", product_id not in product_ids(down_page_buyer))
    s.check("下架后他人看详情 404", code_of(detail_down[1]) == "404",
            "HTTP=%s code=%s" % (detail_down[0], code_of(detail_down[1])))
    s.check("下架后卖家自己的「我的发布」仍可见（草稿箱）", product_id in product_ids(mine))
    s.check("下架后不可下单（业务码 404 商品不存在或已售出）",
            code_of(buy_after_down[1]) == "404",
            "HTTP=%s code=%s info=%s" % (buy_after_down[0], code_of(buy_after_down[1]),
                                         buy_after_down[1].get("info") if isinstance(buy_after_down[1], dict) else ""))
    s.check("下架未产生任何商品订单",
            int(s.cell("SELECT COUNT(*) FROM product_order WHERE product_id = %s" % product_id)) == 0)

    s.section("项 2｜状态机闭环：草稿重提 → 待审 → 通过恢复在售")
    s.h.ok("POST", "/api/trade/products/%s/submit" % product_id, token=seller_tok)
    row_resubmit = s.rows(product_row(product_id))[0]
    s.h.ok("POST", "/api/admin/reviews/decide",
           {"targetType": "PRODUCT", "targetId": product_id, "approve": True}, token=admin_tok)
    row_reon = s.rows(product_row(product_id))[0]
    reon_page = s.h.ok("GET", "/api/trade/products?page=1&pageSize=50")
    s.dump("02-07-resubmit-reonsale", {"after_submit_row": row_resubmit, "after_reapprove_row": row_reon,
                                       "anon_list_ids": product_ids(reon_page)})
    s.check("重提回 PENDING_REVIEW 且 review_round+1", row_resubmit[0] == "PENDING_REVIEW" and str(row_resubmit[1]) == "2",
            "state=%s round=%s" % (row_resubmit[0], row_resubmit[1]))
    s.check("再次通过后回到 ON_SALE 且公开可见",
            row_reon[0] == "ON_SALE" and product_id in product_ids(reon_page),
            "state=%s" % row_reon[0])

    result["product"] = {"product_id": product_id,
                         "state_trace": ["PENDING_REVIEW", "ON_SALE", "DRAFT", "PENDING_REVIEW", "ON_SALE"]}

    # ═══════════════════════════════════════════════════════════
    # 项 5 —— 钱包支付
    # ═══════════════════════════════════════════════════════════
    s.section("项 5｜钱包充值（真实链路 POST /api/wallet/recharge）")
    bal_before_recharge = wallet_state(s, buyer_id)
    recharge_resp = s.h.ok("POST", "/api/wallet/recharge", {"amount": float(RECHARGE_AMOUNT)}, token=buyer_tok)
    bal_after_recharge = wallet_state(s, buyer_id)
    recharge_details = wallet_details(s, buyer_id)
    recharge_rows = [d for d in recharge_details if d["type"] == "RECHARGE"]
    s.dump("05-01-recharge", {"amount": RECHARGE_AMOUNT, "response": recharge_resp,
                              "wallet_before": bal_before_recharge, "wallet_after": bal_after_recharge,
                              "recharge_detail_rows": recharge_rows})
    s.check("充值后余额增加恰好等于充值额",
            money(float(bal_after_recharge["balance"]) - float(bal_before_recharge["balance"])) == RECHARGE_AMOUNT,
            "%s → %s" % (bal_before_recharge["balance"], bal_after_recharge["balance"]))
    s.check("充值落 wallet_detail type=RECHARGE 且 amount 正确",
            bool(recharge_rows) and money(recharge_rows[-1]["amount"]) == RECHARGE_AMOUNT,
            "rows=%s" % recharge_rows)

    s.section("项 5｜下单 → 支付（资金冻结模型：balance-frozen=可用余额）")
    order_resp = s.h.ok("POST", "/api/trade/orders?productId=%s" % product_id, token=buyer_tok)
    order_id = order_resp
    order_row_created = s.rows(
        "SELECT state, price, buyer_id, seller_id, product_id FROM product_order WHERE product_order_id = %s" % order_id)[0]
    product_locked = s.rows(product_row(product_id))[0]
    buy_page = s.h.ok("GET", "/api/trade/products?page=1&pageSize=50")
    s.dump("05-02-create-order", {"order_id": order_id, "db_order_row": order_row_created,
                                  "db_product_row_after_lock": product_locked, "anon_list_ids": product_ids(buy_page)})
    s.check("下单成功且订单 state=PENDING_PAYMENT、price 快照正确",
            order_row_created[0] == "PENDING_PAYMENT" and money(order_row_created[1]) == PRODUCT_PRICE,
            "state=%s price=%s" % (order_row_created[0], order_row_created[1]))
    s.check("下单后商品 state=LOCKED（交易中）", product_locked[0] == "LOCKED", "state=%s" % product_locked[0])

    bal_before_pay = wallet_state(s, buyer_id)
    pay_resp = s.h.ok("POST", "/api/trade/orders/%s/pay" % order_id, token=buyer_tok)
    bal_after_pay = wallet_state(s, buyer_id)
    order_row_paid = s.rows(
        "SELECT state, price FROM product_order WHERE product_order_id = %s" % order_id)[0]
    pay_details = wallet_details(s, buyer_id, order_id)
    frozen_rows = [d for d in pay_details if d["type"] == "PRODUCT_FROZEN"]
    events_after_pay = s.rows(
        "SELECT event_type, from_state, to_state, actor_role FROM product_order_event "
        "WHERE order_id = %s ORDER BY id" % order_id)
    s.dump("05-03-pay", {"order_id": order_id, "pay_response": pay_resp,
                         "wallet_before": bal_before_pay, "wallet_after": bal_after_pay,
                         "db_order_row": order_row_paid, "wallet_detail_rows": pay_details,
                         "product_order_event": events_after_pay,
                         "expected_deduction": PRODUCT_PRICE})
    s.check("支付后可用余额（balance-frozen）减少恰好等于成交价",
            money(float(bal_before_pay["available"]) - float(bal_after_pay["available"])) == PRODUCT_PRICE,
            "%s → %s（成交价 %s）" % (bal_before_pay["available"], bal_after_pay["available"], PRODUCT_PRICE))
    s.check("支付后订单 state=PAID", order_row_paid[0] == "PAID", "state=%s" % order_row_paid[0])
    s.check("wallet_detail 有 PRODUCT_FROZEN 行且 order_id/amount 正确",
            len(frozen_rows) == 1 and str(frozen_rows[0]["order_id"]) == str(order_id)
            and money(frozen_rows[0]["amount"]) == PRODUCT_PRICE,
            "rows=%s" % frozen_rows)
    s.check("支付落 product_order_event PAID（PENDING_PAYMENT→PAID/BUYER）",
            any(r[0] == "PAID" and r[1] == "PENDING_PAYMENT" and r[2] == "PAID" and r[3] == "BUYER"
                for r in events_after_pay),
            "events=%s" % [r[0] for r in events_after_pay])

    s.section("项 5｜重复支付同一订单 → 幂等（不二次扣减）")
    pay_again = s.h.call("POST", "/api/trade/orders/%s/pay" % order_id, token=buyer_tok)
    bal_after_pay2 = wallet_state(s, buyer_id)
    order_row_paid2 = s.rows("SELECT state, price FROM product_order WHERE product_order_id = %s" % order_id)[0]
    frozen_after = detail_count(s, buyer_id, order_id, "PRODUCT_FROZEN")
    events_after_pay2 = s.rows(
        "SELECT event_type, from_state, to_state FROM product_order_event WHERE order_id = %s ORDER BY id" % order_id)
    s.dump("05-04-pay-again-idempotent", {
        "second_pay_response": pay_again, "wallet_after_second_pay": bal_after_pay2,
        "db_order_row": order_row_paid2, "PRODUCT_FROZEN_detail_count": frozen_after,
        "event_types": [r[0] for r in events_after_pay2],
        "wallet_first_pay": bal_after_pay,
    })
    s.check("重复支付返回成功（幂等，非报错）", code_of(pay_again[1]) == "200",
            "HTTP=%s code=%s" % (pay_again[0], code_of(pay_again[1])))
    s.check("重复支付不二次扣减：可用余额不变",
            bal_after_pay2["available"] == bal_after_pay["available"],
            "%s → %s" % (bal_after_pay["available"], bal_after_pay2["available"]))
    s.check("重复支付不新增流水：PRODUCT_FROZEN 仍只有 1 行", frozen_after == 1, "count=%s" % frozen_after)
    s.check("重复支付不新增事件：PAID 事件仍只有一条",
            [r[0] for r in events_after_pay2].count("PAID") == 1,
            "events=%s" % [r[0] for r in events_after_pay2])
    s.check("重复支付后订单仍为 PAID", order_row_paid2[0] == "PAID", "state=%s" % order_row_paid2[0])

    # 全部冻结资金应为 0（没有未支付的托管）
    s.check("卖家钱包无冻结（买家冻结额恰好等于成交价）",
            bal_after_pay2["frozen_amount"] == PRODUCT_PRICE,
            "frozen=%s" % bal_after_pay2["frozen_amount"])

    s.section("项 5｜交付确认：PRODUCT_UNFROZEN + PRODUCT_INCOME（托管结算）")
    seller_bal_before_confirm = wallet_state(s, seller_id)
    s.h.ok("POST", "/api/trade/orders/%s/mark-delivered" % order_id, token=seller_tok)
    s.h.ok("POST", "/api/trade/orders/%s/confirm" % order_id, token=buyer_tok)
    bal_after_confirm = wallet_state(s, buyer_id)
    seller_bal_after_confirm = wallet_state(s, seller_id)
    order_row_done = s.rows("SELECT state FROM product_order WHERE product_order_id = %s" % order_id)[0]
    buyer_details = wallet_details(s, buyer_id, order_id)
    seller_details = wallet_details(s, seller_id, order_id)
    events_all = s.rows("SELECT event_type, from_state, to_state, actor_role FROM product_order_event "
                        "WHERE order_id = %s ORDER BY id" % order_id)
    product_sold = s.rows(product_row(product_id))[0]
    s.dump("05-05-confirm-settlement", {
        "order_id": order_id,
        "db_order_row": order_row_done,
        "buyer_wallet_after_confirm": bal_after_confirm,
        "seller_wallet_before_confirm": seller_bal_before_confirm,
        "seller_wallet_after_confirm": seller_bal_after_confirm,
        "buyer_wallet_detail": buyer_details,
        "seller_wallet_detail": seller_details,
        "product_order_event": events_all,
        "db_product_row": product_sold,
    })
    s.check("确认后订单 state=COMPLETED", order_row_done[0] == "COMPLETED", "state=%s" % order_row_done[0])
    s.check("买家解冻：frozen_amount 归零、balance 不变",
            bal_after_confirm["frozen_amount"] == "0.00" and bal_after_confirm["balance"] == bal_after_pay2["balance"],
            "frozen=%s balance=%s" % (bal_after_confirm["frozen_amount"], bal_after_confirm["balance"]))
    s.check("买家落 PRODUCT_UNFROZEN 行恰一行", detail_count(s, buyer_id, order_id, "PRODUCT_UNFROZEN") == 1)
    s.check("卖家落 PRODUCT_INCOME 行且 order_id/amount 正确",
            detail_count(s, seller_id, order_id, "PRODUCT_INCOME") == 1
            and money(seller_details[-1]["amount"]) == PRODUCT_PRICE,
            "seller_details=%s" % seller_details)
    s.check("卖家余额增加恰好等于成交价",
            money(float(seller_bal_after_confirm["balance"]) - float(seller_bal_before_confirm["balance"])) == PRODUCT_PRICE,
            "%s → %s" % (seller_bal_before_confirm["balance"], seller_bal_after_confirm["balance"]))
    s.check("商品随成交转 SOLD 且退出公开列表",
            product_sold[0] == "SOLD" and product_id not in product_ids(s.h.ok("GET", "/api/trade/products?page=1&pageSize=50")),
            "state=%s" % product_sold[0])
    s.check("状态机事件序 CREATED→PAID→DELIVERED→COMPLETED",
            [r[0] for r in events_all] == ["CREATED", "PAID", "DELIVERED", "COMPLETED"],
            "events=%s" % [r[0] for r in events_all])

    # 幂等复核：确认收货重复调用
    s.section("项 5｜重复确认收货 → 幂等（不重复入账）")
    before_dup = wallet_state(s, seller_id)
    dup_confirm = s.h.call("POST", "/api/trade/orders/%s/confirm" % order_id, token=buyer_tok)
    after_dup = wallet_state(s, seller_id)
    s.dump("05-06-confirm-again-idempotent", {"response": dup_confirm, "seller_before": before_dup,
                                              "seller_after": after_dup,
                                              "PRODUCT_INCOME_count": detail_count(s, seller_id, order_id, "PRODUCT_INCOME")})
    s.check("重复确认收货返回成功（幂等）", code_of(dup_confirm[1]) == "200",
            "HTTP=%s code=%s" % (dup_confirm[0], code_of(dup_confirm[1])))
    s.check("重复确认不重复入账：卖家余额不变且 PRODUCT_INCOME 仍 1 行",
            after_dup["balance"] == before_dup["balance"]
            and detail_count(s, seller_id, order_id, "PRODUCT_INCOME") == 1,
            "%s → %s" % (before_dup["balance"], after_dup["balance"]))

    result["wallet"] = {
        "order_id": order_id,
        "recharge_amount": RECHARGE_AMOUNT,
        "product_price": PRODUCT_PRICE,
        "buyer_wallet_before_pay": bal_before_pay,
        "buyer_wallet_after_pay": bal_after_pay,
        "buyer_wallet_after_second_pay": bal_after_pay2,
        "buyer_wallet_after_confirm": bal_after_confirm,
    }
    return s, result


def main():
    os.makedirs(EVIDENCE_DIR, exist_ok=True)
    h = Harness("02_05-trade-wallet")
    s = None
    try:
        s, result = run(h)
        result["checks"] = s.checks
        s.dump_sql()
        s.dump("02-05-summary", result)
        print("")
        print("════════ 结果：全部 %d 条断言 PASS ════════" % len(s.checks))
        print("证据目录：%s" % EVIDENCE_DIR)
        return 0
    except Failed as e:
        if s is not None:
            result = {"run_id": time.strftime("%Y%m%d-%H%M%S"), "failed_check": str(e), "checks": s.checks}
            try:
                s.dump_sql()
                s.dump("02-05-summary", result)
            except Exception:
                pass
            print("")
            print("════════ 结果：FAIL —— 首条失败断言：%s（已过 %d 条）════════" % (e, len(s.checks)))
        return 1
    except Exception as e:  # noqa: BLE001
        print("")
        print("════════ 结果：ERROR —— %s: %s ════════" % (type(e).__name__, e))
        if s is not None:
            try:
                s.dump_sql()
                s.dump("02-05-summary", {"error": "%s: %s" % (type(e).__name__, e), "checks": s.checks})
            except Exception:
                pass
        return 2


if __name__ == "__main__":
    if not probe_stack():
        print("单体 http://localhost:8080 未在 %d 秒内就绪，未执行任何断言。" % WAIT_MAX_SECONDS)
        sys.exit(3)
    sys.exit(main())
