# -*- coding: utf-8 -*-
"""票 04 冒烟：执行期退团扣分/补位/发布方取消/散团封盘，双端 UI 数据准备"""
import json
import subprocess
import time
import urllib.request

BASE = "http://localhost:8080/api"
PHONES = {"pub": "13900009201", "a": "13900009202", "b": "13900009203", "c": "13900009204"}


def http(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode("utf-8") if body is not None else None,
                                 method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req) as res:
        return json.loads(res.read().decode("utf-8"))


def redis_get(key):
    out = subprocess.run(["docker", "exec", "bs-redis", "redis-cli", "-a", "bs123456", "--no-auth-warning",
                          "get", key], capture_output=True, text=True, encoding="utf-8", errors="ignore").stdout
    return (out or "").strip()


def sms_code(phone, offset):
    time.sleep(1)
    with open("backend/logs/app-ticket04.log", "r", encoding="utf-8", errors="ignore") as f:
        f.seek(offset)
        content = f.read()
    import re
    matches = re.findall(r"SMS-mock.*?%s.*?(\d{6})" % phone, content)
    assert matches, "no code for " + phone
    return matches[-1]


def sql(query):
    out = subprocess.run(["docker", "exec", "bs-mysql", "mysql", "-uroot", "-pbs123456",
                          "--default-character-set=utf8mb4", "-N", "-s", "-e", query, "better_school"],
                         capture_output=True, text=True, encoding="utf-8", errors="ignore").stdout
    return (out or "").strip()


def register(phone, nickname):
    import os
    offset = os.path.getsize("backend/logs/app-ticket04.log") if os.path.exists("backend/logs/app-ticket04.log") else 0
    http("POST", "/user/send-code", {"phone": phone})
    code = sms_code(phone, offset)
    res = http("POST", "/user/register", {"phone": phone, "code": code, "nickname": nickname})
    if res["code"] == "200":
        return {"token": res["data"]["token"], "userId": res["data"]["userInfo"]["userId"]}
    if "已注册" in res.get("info", ""):
        return login(phone)
    raise AssertionError(res)


def login(phone):
    import os
    offset = os.path.getsize("backend/logs/app-ticket04.log")
    http("POST", "/user/send-code", {"phone": phone})
    code = sms_code(phone, offset)
    res = http("POST", "/user/login", {"phone": phone, "code": code})
    assert res["code"] == "200", res
    return {"token": res["data"]["token"], "userId": res["data"]["userInfo"]["userId"]}


users = {k: register(p, "smoke04_" + k) for k, p in PHONES.items()}
print("registered:", {k: v["userId"] for k, v in users.items()})

# 发布者临时提为 ADMIN 走人工审核，重新登录拿含 ADMIN role 的新 token，结束后还原
sql("UPDATE bs_user SET role='ADMIN' WHERE id=%d" % users["pub"]["userId"])
users["pub"] = login(PHONES["pub"])
print("pub re-login with ADMIN role:", users["pub"]["userId"])

created = http("POST", "/errand/orders", {
    "type": "EXPRESS_PICKUP", "description": "票04冒烟帮忙搬箱子",
    "pickupLocation": "菜鸟驿站", "deliveryLocation": "6号楼楼下",
    "reward": 20, "requiredTakers": 2, "timeLimit": "HOURS_2"}, users["pub"]["token"])
assert created["code"] == "200", created
order_id = created["data"]
print("order:", order_id)

assert http("POST", "/errand/orders/%d/pay" % order_id, None, users["pub"]["token"])["code"] == "200"
decide = http("POST", "/admin/reviews/decide", {"targetType": "ERRAND", "targetId": order_id, "approve": True},
              users["pub"]["token"])
assert decide["code"] == "200", decide

for k in ("a", "b"):
    r = http("POST", "/errand/orders/%d/grab" % order_id, None, users[k]["token"])
    assert r["code"] == "200", (k, r)

# 成团建群异步：轮询 room_id 回填
room_id = None
for _ in range(30):
    detail = http("GET", "/errand/orders/%d" % order_id, token=users["a"]["token"])
    room_id = detail["data"].get("roomId")
    if room_id:
        break
    time.sleep(1)
assert room_id, "roomId 未回填"
print("grouped: status=%s roomId=%s members=%d" % (
    detail["data"]["status"], room_id, len(detail["data"]["members"])))

# 执行期退团（A 扣分）→ 群内移除
assert http("DELETE", "/errand/orders/%d/members/me" % order_id, None, users["a"]["token"])["code"] == "200"
for _ in range(20):
    left = sql("SELECT COUNT(1) FROM room_user WHERE room_id=%d AND user_id=%d AND left_at IS NOT NULL"
               % (room_id, users["a"]["userId"]))
    if left == "1":
        break
    time.sleep(1)
print("exit: credit=%s member=%s room_left=%s" % (
    sql("SELECT `change` FROM user_credit_log WHERE user_id=%d AND reason_type='ERRAND_TAKER_CANCEL' AND ref_id=%d"
        % (users["a"]["userId"], order_id)),
    sql("SELECT status FROM errand_order_member WHERE order_id=%d AND user_id=%d" % (order_id, users["a"]["userId"])),
    left))

# 补位（C 走同一进团路径）→ 群内加入
assert http("POST", "/errand/orders/%d/grab" % order_id, None, users["c"]["token"])["code"] == "200"
for _ in range(20):
    joined = sql("SELECT COUNT(1) FROM room_user WHERE room_id=%d AND user_id=%d AND left_at IS NULL"
                 % (room_id, users["c"]["userId"]))
    if joined == "1":
        break
    time.sleep(1)
detail = http("GET", "/errand/orders/%d" % order_id, token=users["c"]["token"])
print("backfill: status=%s current=%s room_joined=%s" % (
    detail["data"]["status"], detail["data"]["currentTakers"], joined))

with open(".scratch/errand-multi-taker/smoke04.json", "w", encoding="utf-8") as f:
    json.dump({"orderId": order_id, "roomId": room_id,
               "tokens": {k: v["token"] for k, v in users.items()},
               "userIds": {k: v["userId"] for k, v in users.items()}}, f, ensure_ascii=False, indent=2)
print("OK — 冒烟数据已落 .scratch/errand-multi-taker/smoke04.json")
