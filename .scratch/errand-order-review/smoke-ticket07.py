# -*- coding: utf-8 -*-
"""票 07 全链路冒烟：真实 compose（MinIO/RocketMQ/xxl-job）+ 8080 新 jar
链路：上传 → 创建 → 支付 →（真实 MQ 消费者通知）→ 管理员裁决 通过/拒绝 两条链
断言：进入审核通知、裁决后可见性、退款流水、余额、拒绝理由（详情+通知）
前置：backend docker compose up、mvn package 后 java -jar 起 8080、日志 logs/app-ticket07.log
"""
import base64
import json
import re
import subprocess
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
BASE = "http://localhost:8080"
LOG = r"C:/Users/a1368/Desktop/better_school/backend/logs/app-ticket07.log"

PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)

passed = []


def check(name, ok, detail=""):
    passed.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ((" | " + str(detail)) if detail else ""))


def call(method, path, body=None, token=None, raw=None, ctype="application/json"):
    url = BASE + path
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(payload)
        except Exception:
            return e.code, payload


def sql(query):
    r = subprocess.run(
        ["docker", "exec", "bs-mysql", "mysql", "-uroot", "-pbs123456", "better_school",
         "--default-character-set=utf8mb4", "-N", "-e", query],
        check=True, capture_output=True)
    return r.stdout.decode("utf-8", errors="replace").strip()


def read_code(phone, offset):
    with open(LOG, "rb") as f:
        f.seek(offset)
        text = f.read().decode("utf-8", errors="replace")
    m = re.findall(u"向 %s 发送验证码：(\\d{6})" % phone, text)
    return m[-1] if m else None


def log_end():
    with open(LOG, "rb") as f:
        return f.seek(0, 2)


def register_or_login(phone, nickname):
    offset = log_end()
    call("POST", "/api/user/send-code", {"phone": phone})
    code = None
    for _ in range(30):
        time.sleep(0.3)
        code = read_code(phone, offset)
        if code:
            break
    assert code, "no code for " + phone
    st, r = call("POST", "/api/user/register", {"phone": phone, "code": code, "nickname": nickname})
    if r.get("code") != "200":  # 已注册过：重发码走登录（验证码单次消费）
        offset = log_end()
        call("POST", "/api/user/send-code", {"phone": phone})
        code = None
        for _ in range(30):
            time.sleep(0.3)
            code = read_code(phone, offset)
            if code:
                break
        st, r = call("POST", "/api/user/login", {"phone": phone, "code": code})
    assert r.get("code") == "200", r
    return r["data"]["token"], r["data"]["userInfo"]["userId"]


def relogin(phone):
    offset = log_end()
    call("POST", "/api/user/send-code", {"phone": phone})
    code = None
    for _ in range(30):
        time.sleep(0.3)
        code = read_code(phone, offset)
        if code:
            break
    st, r = call("POST", "/api/user/login", {"phone": phone, "code": code})
    assert r.get("code") == "200", r
    return r["data"]["token"], r["data"]["userInfo"]["userId"]


def wait_notification(user_id, contains, timeout=30):
    """真实链路：outbox 即时发送 → RocketMQ → 消费者落通知，轮询数据库等通知出现"""
    deadline = time.time() + timeout
    query = ("SELECT content FROM notification WHERE user_id=%d ORDER BY id" % user_id)
    while time.time() < deadline:
        rows = sql(query)
        if contains in rows:
            return True
        time.sleep(1)
    return False


def wallet_of(user_id):
    row = sql("SELECT balance, frozen_amount FROM virtual_wallet WHERE user_id=%d" % user_id)
    balance, frozen = row.split("\t")
    return float(balance), float(frozen)


def refund_count(order_id, ttype):
    out = sql("SELECT COUNT(*) FROM wallet_transaction WHERE order_id=%d AND type='%s'" % (order_id, ttype))
    return int(out)


def create_and_pay(token, description, images, expect_ok=True):
    body = {
        "type": "EXPRESS_PICKUP",
        "description": description,
        "pickupLocation": "菜鸟驿站",
        "deliveryLocation": "6号楼下",
        "reward": 10,
        "timeLimit": "HOURS_2",
    }
    if images:
        body["images"] = images
    st, r = call("POST", "/api/errand/orders", body, token=token)
    if not expect_ok:
        return r
    assert r.get("code") == "200", r
    order_id = r["data"]
    st, r = call("POST", "/api/errand/orders/%d/pay" % order_id, token=token)
    assert r.get("code") == "200", r
    return order_id


# ---------- 准备：作者 + 管理员 ----------
author_token, author_id = register_or_login("13700007001", "冒烟作者")
admin_token, admin_id = register_or_login("13700007002", "冒烟管理员")
sql("UPDATE bs_user SET role='ADMIN' WHERE id=%d" % admin_id)
admin_token, admin_id = relogin("13700007002")
print("author=%d admin=%d" % (author_id, admin_id))

# ---------- 上传配图（MinIO） ----------
def upload_png(token, name):
    boundary = "----bsb" + str(time.time()).replace(".", "")
    body = (("--%s\r\n" % boundary).encode()
            + ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % name).encode()
            + b"Content-Type: image/png\r\n\r\n"
            + PNG
            + ("\r\n--%s--\r\n" % boundary).encode())
    return call("POST", "/api/files/upload", raw=body, token=token,
                ctype="multipart/form-data; boundary=" + boundary)


st, r = upload_png(author_token, "smoke-a.png")
img_a = r["data"]["url"]
st, r = upload_png(author_token, "smoke-b.png")
img_b = r["data"]["url"]
check("MinIO 上传返回可访问 URL", bool(img_a and img_b))

# ---------- 初筛硬拦截（对照） ----------
r = create_and_pay(author_token, "帮我代考一下", None, expect_ok=False)
check("初筛命中创建失败不落库",
      r.get("code") == "500" and "违规" in r.get("info", "")
      and sql("SELECT COUNT(*) FROM errand_order WHERE description='帮我代考一下'") == "0",
      r.get("info"))

# ---------- 链路 A：通过 ----------
balance_before, _ = wallet_of(author_id)
order_ok = create_and_pay(author_token, "冒烟-通过链路订单，请看配图", [img_a])
check("链路A 支付成功", order_ok > 0)
check("链路A 作者收到进入人工审核通知", wait_notification(author_id, "人工审核"))
st, r = call("GET", "/api/moderation/queue?targetType=ERRAND&page=1&pageSize=20", token=admin_token)
queue_items = r["data"]["list"] if r.get("code") == "200" else []
queue_item = next((i for i in queue_items if i.get("targetId") == order_ok), None)
check("链路A 进入审核队列且带出配图",
      queue_item is not None and len(queue_item.get("images") or []) > 0,
      queue_item.get("images") if queue_item else queue_items)
st, r = call("POST", "/api/admin/reviews/decide",
             {"targetType": "ERRAND", "targetId": order_ok, "approve": True}, token=admin_token)
check("链路A 管理员裁决通过", r.get("code") == "200", r.get("info"))
st, r = call("GET", "/api/errand/orders?page=1&pageSize=20", token=None)
public_ids = [o["orderId"] for o in r["data"]["list"]] if r.get("code") == "200" else []
check("链路A 对外列表可见", order_ok in public_ids)
check("链路A 作者收到通过通知", wait_notification(author_id, "通过审核"))

# ---------- 链路 B：拒绝 ----------
order_no = create_and_pay(author_token, "冒烟-拒绝链路订单", [img_b])
check("链路B 支付成功", order_no > 0)
check("链路B 作者收到进入人工审核通知", wait_notification(author_id, "人工审核"))
_, frozen_after_pay = wallet_of(author_id)
check("链路B 支付后冻结悬赏", frozen_after_pay > 0, frozen_after_pay)
reason = "配图与描述不符，请补充后重发"
st, r = call("POST", "/api/admin/reviews/decide",
             {"targetType": "ERRAND", "targetId": order_no, "approve": False, "reason": reason},
             token=admin_token)
check("链路B 管理员裁决拒绝（含理由）", r.get("code") == "200", r.get("info"))
st, r = call("GET", "/api/errand/orders/%d" % order_no, token=author_token)
check("链路B 状态 REJECTED 且详情带拒绝理由",
      r.get("code") == "200" and r["data"]["status"] == "REJECTED" and r["data"].get("rejectReason") == reason,
      r.get("data", {}).get("rejectReason"))
st, r = call("GET", "/api/errand/orders?page=1&pageSize=20", token=None)
public_ids = [o["orderId"] for o in r["data"]["list"]] if r.get("code") == "200" else []
check("链路B 不对外可见", order_no not in public_ids)
check("链路B 退款流水（UNFROZEN）一条", refund_count(order_no, "UNFROZEN") == 1)
balance_end, frozen_end = wallet_of(author_id)
# 链路A 订单已 PUBLISHED：其悬赏按设计保持冻结至完成；此处只断言 B 的 10 元解冻退回
check("链路B 解冻恰好退回 B 的悬赏且余额不变",
      frozen_end == frozen_after_pay - 10 and abs(balance_end - balance_before) < 0.001,
      "balance %s frozen %s" % (balance_end, frozen_end))
check("链路B 作者收到含理由的拒绝通知", wait_notification(author_id, reason))

print("----")
print("SMOKE %d/%d" % (sum(passed), len(passed)))
sys.exit(0 if all(passed) else 1)
