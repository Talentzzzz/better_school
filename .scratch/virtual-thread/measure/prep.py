# -*- coding: utf-8 -*-
"""票 04 压测数据准备：建用户、发帖子/商品并审核通过、充值钱包，落 seed.json。

只做数据准备，不做压测。压测见 loadtest.py。
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)

BASE = "http://localhost:8080"
LOG = None


def call(method, path, body=None, token=None, raw=None, ctype="application/json", timeout=30):
    url = BASE + path
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(payload)
        except Exception:
            return e.code, payload


def read_code(phone, offset):
    with open(LOG, "rb") as f:
        f.seek(offset)
        text = f.read().decode("utf-8", errors="replace")
    m = re.findall(u"向 %s 发送验证码：(\\d{6})" % phone, text)
    return m[-1] if m else None


def log_offset():
    with open(LOG, "rb") as f:
        f.seek(0, 2)
        return f.tell()


def sms_login(phone, register=False, nickname="u"):
    """取一条**未使用过**的验证码（同一号码二次登录必须取最后一条且不同于上次已用）。"""
    off = log_offset()
    st, r = call("POST", "/api/user/send-code", {"phone": phone})
    code = None
    for _ in range(40):
        time.sleep(0.3)
        code = read_code(phone, off)
        if code and code != sms_login.used.get(phone):
            break
    assert code, "no fresh code for " + phone
    sms_login.used[phone] = code
    path = "/api/user/register" if register else "/api/user/login"
    body = {"phone": phone, "code": code, "nickname": nickname} if register else {"phone": phone, "code": code}
    st, r = call("POST", path, body)
    return r


sms_login.used = {}


def login_or_register(phone, nickname):
    r = sms_login(phone, register=True, nickname=nickname)
    if r.get("code") != "200":
        r = sms_login(phone, register=False)
    assert r.get("code") == "200", r
    return r["data"]["token"], r["data"]["userInfo"]["userId"]


def upload_png(token, name):
    boundary = "----bslt" + str(time.time()).replace(".", "")
    body = (("--%s\r\n" % boundary).encode()
            + ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % name).encode()
            + b"Content-Type: image/png\r\n\r\n"
            + PNG
            + ("\r\n--%s--\r\n" % boundary).encode())
    return call("POST", "/api/files/upload", raw=body, token=token,
                ctype="multipart/form-data; boundary=" + boundary)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--posts", type=int, default=40)
    ap.add_argument("--products", type=int, default=20)
    ap.add_argument("--admin-phone", default="13700000001")
    ap.add_argument("--author-phone", default="13700000002")
    ap.add_argument("--reader-phone", default="13700000003")
    args = ap.parse_args()

    global LOG
    LOG = args.log
    assert os.path.exists(LOG), "app log not found: " + LOG

    admin_token, admin_id = login_or_register(args.admin_phone, "压测管理员")
    sql = "UPDATE bs_user SET role='ADMIN' WHERE id=%d" % admin_id
    subprocess.run(["docker", "exec", "bs-mysql", "mysql", "-uroot", "-pbs123456", "better_school", "-e", sql],
                   check=True, capture_output=True)
    admin_token, admin_id = login_or_register(args.admin_phone, "压测管理员")
    print("admin", admin_id)

    author_token, author_id = login_or_register(args.author_phone, "压测作者")
    reader_token, reader_id = login_or_register(args.reader_phone, "压测读者")
    print("author", author_id, "reader", reader_id)

    # 钱包充值：够 errand 压测反复下单支付
    st, r = call("POST", "/api/wallet/recharge", {"amount": 1000000}, token=author_token)
    assert r.get("code") == "200", ("recharge", r)
    print("wallet recharged")

    image_url = None
    st, r = upload_png(author_token, "seed.png")
    if r.get("code") == "200":
        image_url = r["data"]["url"]
    print("image", image_url)

    post_ids = []
    for i in range(args.posts):
        st, r = call("POST", "/api/community/posts", {
            "title": "压测帖子 %d" % i,
            "content": "压测正文 %d：这是一条用于负载测试的帖子内容。" % i,
            "category": "DAILY",
            "images": [image_url] if image_url else None,
        }, token=author_token)
        assert r.get("code") == "200", ("publish", r)
        pid = r["data"]
        st, r = call("POST", "/api/admin/reviews/decide",
                     {"targetType": "POST", "targetId": pid, "approve": True}, token=admin_token)
        assert r.get("code") == "200", ("decide", r)
        post_ids.append(pid)
    print("posts seeded", len(post_ids))

    product_ids = []
    for i in range(args.products):
        st, r = call("POST", "/api/trade/products", {
            "title": "压测商品 %d" % i,
            "description": "压测商品描述 %d" % i,
            "price": 10.0 + i,
            "condition": "LIKE_NEW",
            "tradeLocation": "6号宿舍楼下",
            "category": "DAILY",
        }, token=author_token)
        assert r.get("code") == "200", ("product", r)
        pid = r["data"]
        st, r = call("POST", "/api/admin/reviews/decide",
                     {"targetType": "PRODUCT", "targetId": pid, "approve": True}, token=admin_token)
        assert r.get("code") == "200", ("product decide", r)
        product_ids.append(pid)
    print("products seeded", len(product_ids))

    out = {
        "authorToken": author_token, "authorId": author_id,
        "readerToken": reader_token, "readerId": reader_id,
        "adminToken": admin_token, "adminId": admin_id,
        "postIds": post_ids, "productIds": product_ids,
        "imageUrl": image_url,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("OK ->", args.out)


if __name__ == "__main__":
    main()
