# -*- coding: utf-8 -*-
# 票 06 评论点赞冒烟：注册两用户 → B 评论 → 审核通过 → B 点赞帖子/评论 → 校验 likeCount/liked/通知
import json
import random
import subprocess
import urllib.request

BASE = "http://localhost:5173/api"


def api(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(req, data) as resp:
        return json.loads(resp.read())


def register(phone, nickname):
    api("POST", "/user/send-code", {"phone": phone})
    out = subprocess.run(
        ["docker", "exec", "bs-redis", "redis-cli", "-a", "bs123456", "GET", "bs:verify-code:" + phone],
        capture_output=True)
    raw = out.stdout.rstrip(b"\n\r")
    # Redisson Kryo 编码：首字节为长度标记，末字节 = 最后一个字符 | 0x80
    digits = bytes(b for b in raw if 48 <= b <= 57)
    if raw and raw[-1] & 0x80:
        digits += bytes([raw[-1] & 0x7F])
    code = digits.decode()[-6:]
    res = api("POST", "/user/register", {"phone": phone, "code": code, "nickname": nickname})
    assert res["code"] == "200", res
    return res["data"]["token"], res["data"]["userInfo"]["userId"]


a, a_id = register("139%08d" % random.randint(0, 99999998), "smoke-A")
b, b_id = register("139%08d" % random.randint(0, 99999998), "smoke-B")

pid = api("POST", "/community/posts", {"title": "smoke-like", "content": "like smoke", "category": "DAILY"}, a)["data"]
cid = api("POST", "/community/posts/%d/comments" % pid, {"content": "smoke comment"}, b)["data"]["commentId"]

# 冒烟环境：直接 MySQL 把评论置 NORMAL（模拟审核通过，完整链路由集成测试覆盖）
subprocess.run(
    ["docker", "exec", "bs-mysql", "mysql", "-uroot", "-pbs123456", "better_school",
     "-e", "UPDATE post_comment SET status='NORMAL' WHERE id=%d" % cid],
    capture_output=True)

# B 点赞帖子 + A 点赞评论
assert api("POST", "/community/posts/%d/like" % pid, {}, b)["code"] == "200"
assert api("POST", "/community/comments/%d/like" % cid, {}, a)["code"] == "200"
repeat = api("POST", "/community/comments/%d/like" % cid, {}, a)
assert repeat["code"] != "200", "repeat like should fail"

detail = api("GET", "/community/posts/%d" % pid, None, b)["data"]
assert detail["liked"] is True and detail["likeCount"] == 1, detail

comments = api("GET", "/community/posts/%d/comments?page=1&pageSize=10" % pid, None, a)["data"]
row = [c for c in comments["list"] if c["commentId"] == cid][0]
assert row["likeCount"] == 1 and row["liked"] is True, row

notices_a = api("GET", "/chat/notifications?page=1&pageSize=10", None, a)["data"]
assert not [n for n in notices_a["list"] if n["type"] == "COMMENT_LIKED"], "A 赞 B 的评论，通知应发给 B 而非 A"
notices_b = api("GET", "/chat/notifications?page=1&pageSize=10", None, b)["data"]
liked = [n for n in notices_b["list"] if n["type"] == "COMMENT_LIKED"]
assert len(liked) == 1 and liked[0]["senderId"] == a_id, liked
print("notices A:", [(n["type"], n.get("senderId"), n["content"]) for n in notices_a["list"]])
print("notices B:", [(n["type"], n.get("senderId"), n["content"]) for n in notices_b["list"]])

# 取消赞再赞：通知仍一条
assert api("DELETE", "/community/comments/%d/like" % cid, None, a)["code"] == "200"
assert api("POST", "/community/comments/%d/like" % cid, {}, a)["code"] == "200"
notices_b2 = api("GET", "/chat/notifications?page=1&pageSize=10", None, b)["data"]
liked = [n for n in notices_b2["list"] if n["type"] == "COMMENT_LIKED"]
assert len(liked) == 1, liked
print("SMOKE OK")
