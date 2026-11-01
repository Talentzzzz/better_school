# -*- coding: utf-8 -*-
"""票 09 验收 B 账号（REST 侧）动作助手：python smoke-ticket09-b.py <action> [args]"""
import json
import sys
import urllib.request

BASE = "http://localhost:8080/api"
with open(".scratch/chat-impl/tokens.json", "r", encoding="utf-8") as f:
    T = json.load(f)
B_TOKEN = T["b"]["token"]
ROOM = T["roomId"]


def req(method, path, body=None, token=B_TOKEN):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(r) as res:
        return json.loads(res.read().decode("utf-8"))


def out(res):
    print(json.dumps(res, ensure_ascii=False))


action = sys.argv[1]
if action == "send-text":
    out(req("POST", f"/chat/rooms/{ROOM}/messages", {"clientMsgId": sys.argv[3], "msgType": "TEXT", "content": sys.argv[2]}))
elif action == "read":
    out(req("POST", f"/chat/rooms/{ROOM}/read", {}))
elif action == "rooms":
    out(req("GET", "/chat/rooms?page=1&pageSize=50", None))
elif action == "messages":
    out(req("GET", f"/chat/rooms/{ROOM}/messages?limit=50", None))
