# -*- coding: utf-8 -*-
"""票 05 冒烟数据准备：多人单（2 人）成团建群，浏览器端以 taker A 身份走取货/送达/完成/到账/封盘。"""
import json
import os
import re
import subprocess
import time
import urllib.request

BASE = "http://localhost:8080/api"
PHONES = {"pub": "13900009501", "a": "13900009502", "b": "13900009503"}
LOG = "backend/logs/app-ticket05.log"


def http(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode("utf-8") if body is not None else None,
                                 method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req) as res:
        return json.loads(res.read().decode("utf-8"))


def sms_code(phone, offset):
    time.sleep(1)
    with open(LOG, "r", encoding="utf-8", errors="ignore") as f:
        f.seek(offset)
        content = f.read()
    matches = re.findall(r"SMS-mock.*?%s.*?(\d{6})" % phone, content)
    assert matches, "no code for " + phone
    return matches[-1]


def sql(query):
    out = subprocess.run(["docker", "exec", "bs-mysql", "mysql", "-uroot", "-pbs123456",
                          "--default-character-set=utf8mb4", "-N", "-s", "-e", query, "better_school"],
                         capture_output=True, text=True, encoding="utf-8", errors="ignore").stdout
    return (out or "").strip()


def register(phone, nickname):
    offset = os.path.getsize(LOG) if os.path.exists(LOG) else 0
    http("POST", "/user/send-code", {"phone": phone})
    code = sms_code(phone, offset)
    res = http("POST", "/user/register", {"phone": phone, "code": code, "nickname": nickname})
    if res["code"] == "200":
        return {"token": res["data"]["token"], "userId": res["data"]["userInfo"]["userId"]}
    if "已注册" in res.get("info", ""):
        return login(phone)
    raise AssertionError(res)


def login(phone):
    offset = os.path.getsize(LOG)
    http("POST", "/user/send-code", {"phone": phone})
    code = sms_code(phone, offset)
    res = http("POST", "/user/login", {"phone": phone, "code": code})
    assert res["code"] == "200", res
    return {"token": res["data"]["token"], "userId": res["data"]["userInfo"]["userId"]}


users = {k: register(p, "smoke05_" + k) for k, p in PHONES.items()}
print("registered:", {k: v["userId"] for k, v in users.items()})

sql("UPDATE bs_user SET role='ADMIN' WHERE id=%d" % users["pub"]["userId"])
users["pub"] = login(PHONES["pub"])
print("pub re-login with ADMIN role:", users["pub"]["userId"])

created = http("POST", "/errand/orders", {
    "type": "EXPRESS_PICKUP", "description": "票05冒烟一起搬三个箱子",
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

room_id = None
for _ in range(30):
    detail = http("GET", "/errand/orders/%d" % order_id, token=users["a"]["token"])
    room_id = detail["data"].get("roomId")
    if room_id:
        break
    time.sleep(1)
assert room_id, "roomId 未回填"
print("grouped: status=%s roomId=%s current=%s" % (
    detail["data"]["status"], room_id, detail["data"]["currentTakers"]))

with open(".scratch/errand-multi-taker/smoke05.json", "w", encoding="utf-8") as f:
    json.dump({"orderId": order_id, "roomId": room_id, "pubUserId": users["pub"]["userId"],
               "tokens": {k: v["token"] for k, v in users.items()},
               "userIds": {k: v["userId"] for k, v in users.items()}}, f, ensure_ascii=False, indent=2)
print("OK — 冒烟数据已落 .scratch/errand-multi-taker/smoke05.json")
print("aToken=%s aPhone=%s" % (users["a"]["token"], PHONES["a"]))
