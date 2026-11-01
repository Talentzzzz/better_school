# -*- coding: utf-8 -*-
"""票 06 冒烟数据准备：注册作者/管理员，提权，传配图，创建并支付两张跑腿单"""
import base64
import json
import re
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
BASE = "http://localhost:8080"
LOG = r"C:/Users/a1368/Desktop/better_school/backend/logs/app-ticket06.log"

PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)


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


def read_code(phone, expect_after_lines):
    """从应用日志抓 [SMS-mock] 验证码（只看新增部分）"""
    with open(LOG, "rb") as f:
        f.seek(expect_after_lines)
        text = f.read().decode("utf-8", errors="replace")
    m = re.findall(u"向 %s 发送验证码：(\\d{6})" % phone, text)
    return m[-1] if m else None


def register(phone, nickname, offset):
    st, r = call("POST", "/api/user/send-code", {"phone": phone})
    print("send-code", phone, st, r.get("code"))
    for _ in range(20):
        time.sleep(0.3)
        code = read_code(phone, offset)
        if code:
            break
    assert code, "no code for " + phone
    st, r = call("POST", "/api/user/register", {"phone": phone, "code": code, "nickname": nickname})
    if r.get("code") != "200":
        print("register fallback to login:", r.get("info"))
        with open(LOG, "rb") as f:
            f.seek(0, 2)
            new_offset = f.tell()
        st, r2 = call("POST", "/api/user/send-code", {"phone": phone})
        code = None
        for _ in range(20):
            time.sleep(0.3)
            code = read_code(phone, new_offset)
            if code:
                break
        assert code, "no re-login code for " + phone
        st, r = call("POST", "/api/user/login", {"phone": phone, "code": code})
    print("register", nickname, st, r.get("code"))
    return r["data"]["token"], r["data"]["userInfo"]["userId"]


def login(phone, offset):
    st, r = call("POST", "/api/user/send-code", {"phone": phone})
    for _ in range(20):
        time.sleep(0.3)
        code = read_code(phone, offset)
        if code:
            break
    assert code, "no code for " + phone
    st, r = call("POST", "/api/user/login", {"phone": phone, "code": code})
    print("login", phone, st, r.get("code"))
    return r["data"]["token"], r["data"]["userInfo"]["userId"]


with open(LOG, "rb") as f:
    f.seek(0, 2)
    log_offset = f.tell()

author_token, author_id = register("13700006001", "作者用户", log_offset)
with open(LOG, "rb") as f:
    f.seek(0, 2)
    log_offset = f.tell()
admin_token, admin_id = register("13700006002", "审核管理员", log_offset)

# 提权 ADMIN 后重新登录（角色在 JWT 里）
import subprocess

sql = "UPDATE bs_user SET role='ADMIN' WHERE id=%d" % admin_id
subprocess.run(
    ["docker", "exec", "bs-mysql", "mysql", "-uroot", "-pbs123456", "better_school", "-e", sql],
    check=True, capture_output=True,
)
with open(LOG, "rb") as f:
    f.seek(0, 2)
    log_offset = f.tell()
admin_token, admin_id = login("13700006002", log_offset)

# 上传两张配图（作者身份，multipart form-data）
def upload_png(token, name):
    boundary = "----bsb" + str(time.time()).replace(".", "")
    body = (("--%s\r\n" % boundary).encode()
            + ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % name).encode()
            + b"Content-Type: image/png\r\n\r\n"
            + PNG
            + ("\r\n--%s--\r\n" % boundary).encode())
    return call("POST", "/api/files/upload", raw=body, token=token,
                ctype="multipart/form-data; boundary=" + boundary)


urls = []
for name in ("p1.png", "p2.png"):
    st, r = upload_png(author_token, name)
    print("upload", name, st, r.get("code"))
    urls.append(r["data"]["url"])

# 两张订单：先支付无配图单（应先入队），再支付带配图单
def create_and_pay(description, images):
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
    st, r = call("POST", "/api/errand/orders", body, token=author_token)
    print("create", description, st, r.get("code"))
    oid = r["data"]
    st, r = call("POST", "/api/errand/orders/%d/pay" % oid, token=author_token)
    print("pay", oid, st, r.get("code"))
    return oid

oid_no_img = create_and_pay("帮忙取个快递（无图）", None)
oid_with_img = create_and_pay("帮忙取个快递，需要看配图", urls)

# 管理员视角拉队列确认
st, r = call("GET", "/api/moderation/queue?targetType=ERRAND&page=1&pageSize=10", token=admin_token)
print("queue", st, r.get("code"))
print(json.dumps(r["data"], ensure_ascii=False)[:800])

result = {"authorToken": author_token, "adminToken": admin_token, "adminId": admin_id,
          "oidNoImg": oid_no_img, "oidWithImg": oid_with_img, "imageUrls": urls}
with open(r"C:/Users/a1368/Desktop/better_school/.scratch/errand-order-review/smoke-ticket06.json", "w") as f:
    json.dump(result, f)
print("OK")
