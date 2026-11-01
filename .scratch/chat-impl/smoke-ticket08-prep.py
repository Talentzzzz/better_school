# -*- coding: utf-8 -*-
"""票 08 手测准备：A/B 两账号登录 + 建 1v1 会话，token 落 .scratch/chat-impl/tokens.json"""
import json
import re
import time
import urllib.request

BASE = "http://localhost:8080/api"
LOG = "backend/logs/app-ticket08.log"
A_PHONE = "13700006001"
B_PHONE = "13700006002"


def post(path, body, token=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode("utf-8"), method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req) as res:
        return json.loads(res.read().decode("utf-8"))


def get_code(phone, offset):
    time.sleep(1)
    with open(LOG, "r", encoding="utf-8", errors="ignore") as f:
        f.seek(offset)
        content = f.read()
    matches = re.findall(r"SMS-mock.*?%s.*?(\d{6})" % phone, content)
    if not matches:
        raise SystemExit("no code for " + phone)
    return matches[-1]


def login(phone):
    size = __import__("os").path.getsize(LOG)
    post("/user/send-code", {"phone": phone})
    code = get_code(phone, size)
    res = post("/user/login", {"phone": phone, "code": code})
    assert res["code"] == "200", res
    data = res["data"]
    return {"token": data["token"], "userId": data["userInfo"]["userId"]}


a = login(A_PHONE)
b = login(B_PHONE)
room = post("/chat/rooms/direct", {"targetUserId": a["userId"]}, b["token"])
assert room["code"] == "200", room

with open(".scratch/chat-impl/tokens.json", "w", encoding="utf-8") as f:
    json.dump(
        {
            "a": a,
            "b": b,
            "roomId": room["data"]["roomId"],
        },
        f,
        ensure_ascii=False,
        indent=2,
    )
print("OK room=%s a=%s b=%s" % (room["data"]["roomId"], a["userId"], b["userId"]))
