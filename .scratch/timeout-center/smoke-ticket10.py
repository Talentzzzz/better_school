"""票 10 联调冒烟：三类下单 → timeout_register_outbox → 超时中心可见已注册任务（callback_group = better-school）。

前置：dev 库跑过 028 迁移、8080 单体（新 jar）+ 8083 超时中心都在跑、xxl-job 行已导入。
跑法：python .scratch/timeout-center/smoke-ticket10.py        （在仓库根跑，windows 用系统 python 即可）
输出：ASCII 摘要 + .scratch/timeout-center/smoke10-ids.json（供 docker exec 查库断言）
"""
import glob
import json
import os
import re
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
# 起 jar 的 cwd 决定日志落点：backend/better-school/（本票冒烟）或 backend/（IDE 起法）。
# 每轮冒烟换日志文件名（app-ticket10.log / app-ticket10b.log…），取最新改动的那份。
LOG_GLOBS = ["backend/better-school/logs/app-ticket10*.log", "backend/logs/app-ticket10*.log"]

ids = {}
tokens = {}


def log_path():
    for pattern in LOG_GLOBS:
        files = glob.glob(pattern)
        if files:
            return max(files, key=lambda path: os.path.getmtime(path))
    raise SystemExit("找不到应用日志 %s（后端起了吗？）" % LOG_GLOBS)


used_codes = {}


def read_code(phone):
    """验证码从应用日志抓（Redis 是 Kryo 编码，shell 里读不出明文）。

    取日志里**最后一条**匹配：验证码单次消费，重复读同一条会拿到已用过的旧值。
    """
    pattern = re.compile(r"\[SMS-mock\] .*%s.*验证码：(\d{6})" % phone)
    deadline = time.time() + 10
    while time.time() < deadline:
        code = None
        with open(log_path(), encoding="utf-8", errors="ignore") as handle:
            for line in handle:
                match = pattern.search(line)
                if match:
                    code = match.group(1)
        if code and code != used_codes.get(phone):
            used_codes[phone] = code
            return code
        time.sleep(0.3)
    raise SystemExit("日志里没等到 %s 的新验证码" % phone)


def post(path, body, token=None, query=None):
    url = BASE + path + (("?" + query) if query else "")
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else b""
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return json.loads(error.read().decode("utf-8"))


def ok(response, what):
    if response.get("code") != "200":
        raise SystemExit("%s 失败：%s" % (what, json.dumps(response, ensure_ascii=False)))
    return response.get("data")


def login(phone, nickname):
    post("/api/user/send-code", {"phone": phone})
    code = read_code(phone)
    registered = post("/api/user/register", {"phone": phone, "code": code, "nickname": nickname})
    if registered.get("code") != "200":
        # 已注册过：走登录
        post("/api/user/send-code", {"phone": phone})
        code = read_code(phone)
        data = ok(post("/api/user/login", {"phone": phone, "code": code}), "登录 " + phone)
    else:
        data = registered["data"]
    tokens[phone] = data["token"]
    return data["token"]


stamp = str(int(time.time()))[-6:]
seller_phone = "138" + stamp + "01"
buyer_phone = "138" + stamp + "02"
admin_phone = "138" + stamp + "03"

print("== 登录三个账号 ==")
seller = login(seller_phone, "smoke-seller")
buyer = login(buyer_phone, "smoke-buyer")
admin = login(admin_phone, "smoke-admin")
print("phones: seller=%s buyer=%s admin=%s" % (seller_phone, buyer_phone, admin_phone))

# 管理员提权后必须重新登录（后端按 token 里的 role 判权）
import subprocess

subprocess.run(
    ["docker", "exec", "-i", "bs-mysql", "sh", "-c",
     "mysql -uroot -pbs123456 -e \"UPDATE better_school.bs_user SET role='ADMIN' WHERE phone='%s';\"" % admin_phone],
    check=True, capture_output=True)
admin = login(admin_phone, "smoke-admin")

print("== 商品：发布 -> 裁决通过 -> 下单 ==")
product = ok(post("/api/trade/products", {
    "title": "smoke10 旧吉他", "description": "smoke10 自用闲置",
    "images": [], "price": 19.9, "condition": "NINETY_NEW",
    "tradeLocation": "smoke10 图书馆", "category": "BOOKS"}, seller), "发布商品")
ok(post("/api/admin/reviews/decide", {
    "targetType": "PRODUCT", "targetId": product, "approve": True}, admin), "商品裁决")
order = ok(post("/api/trade/orders", None, buyer, "productId=%s" % product), "商品下单")
ids["productId"] = product
ids["productOrderId"] = order
print("productId=%s productOrderId=%s" % (product, order))

print("== 跑腿：发布 ==")
errand = ok(post("/api/errand/orders", {
    "type": "EXPRESS_PICKUP", "description": "smoke10 取快递",
    "pickupLocation": "smoke10 菜鸟驿站", "deliveryLocation": "smoke10 6号楼",
    "reward": 10, "timeLimit": "HOURS_2"}, buyer), "发布跑腿")
ids["errandOrderId"] = errand
print("errandOrderId=%s" % errand)

print("== 求购：发布（进待支付即登记） ==")
want = ok(post("/api/trade/want-to-buy", {
    "title": "smoke10 求购显示器", "description": "smoke10 成色好即可",
    "images": [], "budget": 30, "category": "BOOKS"}, buyer), "发布求购")
ids["wantId"] = want
print("wantId=%s" % want)

print("== 求购：支付（应撤销中心侧在途任务 → 中心侧 CANCELED） ==")
ok(post("/api/trade/want-to-buy/%s/pay" % want, None, buyer), "求购支付")

# 等即时注册落地（afterCommit 虚拟线程 + 中心落库）
time.sleep(3)

with open(".scratch/timeout-center/smoke10-ids.json", "w", encoding="utf-8") as handle:
    json.dump(ids, handle, ensure_ascii=False, indent=2)

print("\nids written to .scratch/timeout-center/smoke10-ids.json")
print(json.dumps(ids, ensure_ascii=True))
