# -*- coding: utf-8 -*-
"""票 05 验收冒烟：三服务 HTTP 冒烟 + Dubbo 用户查询边 + 兑换边(activity->asset)。"""
import json
import re
import time
import urllib.request

BS_LOG = r"C:\Users\a1368\Desktop\better_school\.scratch\service-restructure-impl\verify-05\app-bs-8080.log"
PHONE = "13700006001"


def call(method, path, body=None, token=None):
    req = urllib.request.Request("http://localhost:5173" + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw[:200]


def grab_code():
    time.sleep(1)
    with open(BS_LOG, encoding="utf-8", errors="ignore") as f:
        tail = f.read()[-8000:]
    m = re.findall(r"向 %s 发送验证码[：:](\d{6})" % PHONE, tail)
    return m[-1] if m else None


results = []


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + ("  " + str(detail)[:120] if detail else ""))


# --- 未登录：鉴权信封不变 ---
st, r = call("GET", "/api/points/balance")
record("points/balance 未登录 401", st == 401, r if st != 401 else "")
st, r = call("GET", "/api/referral/invite-code")
record("referral/invite-code 未登录 401", st == 401, r if st != 401 else "")

# --- 登录（验证码走 bs 日志）---
st, r = call("POST", "/api/user/send-code", {"phone": PHONE})
code = grab_code()
assert code, "no sms code in bs log"
st, r = call("POST", "/api/user/login", {"phone": PHONE, "code": code})
token = r["data"]["token"] if st == 200 and r.get("data") else None
record("验证码登录", bool(token), r.get("info"))

# --- Dubbo 用户查询边（activity 两域 ping -> bs）---
st, r = call("GET", "/api/lottery/ping", token=token)
record("lottery/ping 200+用户信息(用户查询边)", st == 200 and r.get("code") == "200"
       and bool(r.get("data", {}).get("nickname") or r.get("data", {}).get("mobile")), r)
st, r = call("GET", "/api/referral/ping", token=token)
record("referral/ping 200+用户信息(用户查询边)", st == 200 and r.get("code") == "200", r)

# --- 活动页数据（URL/响应形状不变）---
st, r = call("GET", "/api/lottery/activity")
record("lottery/activity 200", st == 200 and r.get("code") == "200", r)
st, r = call("GET", "/api/lottery/prizes")
record("lottery/prizes 200", st == 200 and isinstance(r.get("data"), list), type(r.get("data")))
st, r = call("GET", "/api/lottery/draw-chances", token=token)
record("lottery/draw-chances 200", st == 200 and r.get("code") == "200", r)
st, r = call("GET", "/api/lottery/my-prizes", token=token)
record("lottery/my-prizes 200", st == 200 and r.get("code") == "200", r)

# --- 兑换边（activity -> asset Dubbo 扣分）---
st, r = call("GET", "/api/points/balance", token=token)
record("points/balance 200(资产 HTTP)", st == 200 and r.get("code") == "200", r)


def sql(sql_text, db="bs_asset"):
    import subprocess
    cmd = ["docker", "exec", "bs-mysql", "mysql", "-uroot", "-pbs123456", db, "-N", "-e", sql_text]
    out = subprocess.run(cmd, capture_output=True, text=True)
    return out.stdout.strip()


uid = 0
st, r = call("GET", "/api/lottery/ping", token=token)
uid = int((r.get("data") or {}).get("userId") or 0)
if not uid:
    uid = int(sql("SELECT id FROM better_school.bs_user WHERE mobile='%s'" % PHONE, "better_school") or 0)
assert uid, "no uid"
print("uid =", uid)

before = int(sql("SELECT COALESCE(SUM(remain),0) FROM points_lot WHERE user_id=%d AND remain>0 AND expire_at>NOW()" % uid) or 0)
print("balance before =", before)
need = 100 - before
if need > 0:
    sql("INSERT IGNORE INTO points_lot (user_id, amount, remain, expire_at, reason_type, ref_id, created_at) "
        "VALUES (%d, %d, %d, '2027-12-31', 'SMOKE', 0, NOW())" % (uid, need, need))
    before = 100

st, r = call("POST", "/api/lottery/exchange/draw-count", {}, token=token)
ok = st == 200 and r.get("code") == "200"
record("exchange/draw-count 200(兑换边 Dubbo deduct)", ok, r if not ok else r.get("data"))
after = int(sql("SELECT COALESCE(SUM(remain),0) FROM points_lot WHERE user_id=%d AND remain>0 AND expire_at>NOW()" % uid) or 0)
consume = sql("SELECT COUNT(*) FROM points_consume WHERE user_id=%d" % uid)
record("asset 侧余额扣减+流水落行", ok and before - after == 100 and int(consume) >= 1,
       "before=%s after=%s consume_rows=%s" % (before, after, consume))

print("\n== summary ==")
fails = [x for x in results if not x[1]]
print("%d/%d PASS" % (len(results) - len(fails), len(results)))
