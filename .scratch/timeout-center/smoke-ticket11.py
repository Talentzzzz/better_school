"""票 11 全链路实跑验收：真实下单 -> 中心到期 -> 跨 Dubbo 回调 -> 三路关单动作各自生效。

前置：
  - dev 库已跑过 028 迁移（票 10）；xxl-job 里 timeoutTaskScanJob 处于启用态（票 08 的 sql/xxl-job-tasks.sql）
  - 8080 单体（**本票的新 jar**，含回调 Provider）+ 8083 超时中心 都在跑
  - 三者共用 127.0.0.1:8848 的 Nacos（动态 ReferenceConfig 路由的前提）

跑法：python .scratch/timeout-center/smoke-ticket11.py   （在仓库根跑）
做的事：
  1. 建商品订单 / 跑腿单 / 求购（都停在待支付），等即时注册落地到 bs_timeout
  2. 把中心的三条任务 next_fire_time 推到过去（免等 15 分钟），等一个扫描周期（10s）
  3. 断言：中心三条 SUCCESS；业务侧商品单 CANCELLED + 商品 ON_SALE + 搜索同步消息补一条、
     跑腿单 CANCELLED、求购 CANCELLED
输出：ASCII 摘要 + .scratch/timeout-center/smoke11-ids.json
"""
import glob
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
LOG_GLOBS = ["backend/better-school/logs/app-ticket11*.log", "backend/logs/app-ticket11*.log"]
MYSQL = ["docker", "exec", "-i", "bs-mysql", "sh", "-c"]

ids = {}
used_codes = {}


# ---------- 基础设施 ----------

def log_path():
    for pattern in LOG_GLOBS:
        files = glob.glob(pattern)
        if files:
            return max(files, key=os.path.getmtime)
    raise SystemExit("找不到应用日志 %s（8080 起了吗？）" % LOG_GLOBS)


def sql(database, statement):
    """跑一条 SQL，返回 tab 分隔的行列表（去表头）。走 docker exec，密码 bs123456。"""
    command = ("mysql -uroot -pbs123456 --default-character-set=utf8mb4 -N -B %s -e \"%s\""
               % (database, statement.replace('"', '\\"')))
    result = subprocess.run(MYSQL + [command], capture_output=True, text=True, encoding="utf-8", errors="ignore")
    if result.returncode != 0:
        raise SystemExit("SQL 失败：%s\n%s" % (statement, result.stderr))
    return [line for line in result.stdout.splitlines() if line.strip()]


def scalar(database, statement):
    rows = sql(database, statement)
    return rows[0].strip() if rows else None


def read_code(phone):
    """验证码从应用日志抓（Redis 是 Kryo 编码）。取最后一条匹配：验证码单次消费，读旧值会登录失败。"""
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
        post("/api/user/send-code", {"phone": phone})
        code = read_code(phone)
        data = ok(post("/api/user/login", {"phone": phone, "code": code}), "登录 " + phone)
    else:
        data = registered["data"]
    return data["token"]


# ---------- Nacos：回调 Provider 是否以应用名为 group 注册 ----------

def nacos_services():
    request = urllib.request.Request(
        "http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=200&namespaceId=public")
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8")).get("doms", [])


# ---------- 主流程 ----------

stamp = str(int(time.time()))[-6:]
seller_phone, buyer_phone, admin_phone = "138" + stamp + "11", "138" + stamp + "12", "138" + stamp + "13"

print("== 0. Dubbo 注册面：回调 Provider 的 group ==")
# Nacos 里的服务名格式：providers:<接口全名>:<version>:<group>（默认 version 为空 → 中间是空的 `::`）
callback_services = [name for name in nacos_services() if "ITimeoutTaskCallback" in name]
print("callback services: %s" % callback_services)
if not any(name.endswith(":better-school") for name in callback_services):
    raise SystemExit("Nacos 里没有 group=better-school 的 ITimeoutTaskCallback（${spring.application.name} 没解析？）")

print("== 1. 登录三个账号 ==")
seller, buyer = login(seller_phone, "smoke11-seller"), login(buyer_phone, "smoke11-buyer")
login(admin_phone, "smoke11-admin")
subprocess.run(MYSQL + ["mysql -uroot -pbs123456 -e \"UPDATE better_school.bs_user SET role='ADMIN' WHERE phone='%s';\"" % admin_phone],
               check=True, capture_output=True)
admin = login(admin_phone, "smoke11-admin")
print("phones: seller=%s buyer=%s admin=%s" % (seller_phone, buyer_phone, admin_phone))

print("== 2. 商品：发布 -> 裁决通过 -> 下单（停在待支付） ==")
product = ok(post("/api/trade/products", {
    "title": "smoke11 旧吉他", "description": "smoke11 自用闲置", "images": [], "price": 19.9,
    "condition": "NINETY_NEW", "tradeLocation": "smoke11 图书馆", "category": "BOOKS"}, seller), "发布商品")
ok(post("/api/admin/reviews/decide", {"targetType": "PRODUCT", "targetId": product, "approve": True}, admin), "商品裁决")
order = ok(post("/api/trade/orders", None, buyer, "productId=%s" % product), "商品下单")

print("== 3. 跑腿：发布（停在待支付） ==")
errand = ok(post("/api/errand/orders", {
    "type": "EXPRESS_PICKUP", "description": "smoke11 取快递", "pickupLocation": "smoke11 菜鸟驿站",
    "deliveryLocation": "smoke11 6号楼", "reward": 10, "timeLimit": "HOURS_2"}, buyer), "发布跑腿")

print("== 4. 求购：发布（停在待支付；不支付，留给超时回调关单） ==")
want = ok(post("/api/trade/want-to-buy", {
    "title": "smoke11 求购显示器", "description": "smoke11 成色好即可", "images": [], "budget": 30,
    "category": "BOOKS"}, buyer), "发布求购")

ids.update(productId=product, productOrderId=order, errandOrderId=errand, wantId=want)
print("ids: %s" % json.dumps(ids))

print("== 5. 等即时注册落到中心（bs_timeout），检查 callback_group ==")
# 业务侧三个自增 id 各表独立，可能撞号，所以一律以 task_type|biz_id 作键
KEYS = (("PRODUCT_ORDER_PAY_TIMEOUT", order), ("ERRAND_PAY_TIMEOUT", errand), ("WANT_TO_BUY_PAY_TIMEOUT", want))
in_clause = ",".join("('%s',%d)" % key for key in KEYS)
deadline = time.time() + 20
registered = {}
while time.time() < deadline:
    rows = sql("bs_timeout", "SELECT CONCAT(task_type,'|',biz_id,'|',status,'|',callback_group) FROM ("
               + " UNION ALL ".join(
                   "SELECT task_type,biz_id,status,callback_group FROM timeout_task_%02d WHERE (task_type,biz_id) IN (%s)"
                   % (slot, in_clause) for slot in range(16)) + ") t")
    registered = {row.rsplit("|", 2)[0]: row for row in rows}
    if len(registered) >= 3:
        break
    time.sleep(2)
print("center tasks: %s" % list(registered.values()))
if len(registered) < 3:
    raise SystemExit("中心只看到 %d 条任务（应为 3）：%s" % (len(registered), list(registered.values())))
bad_group = [row for row in registered.values() if not row.endswith("better-school")]
if bad_group:
    raise SystemExit("回调 group 不是应用名：%s" % bad_group)
if any("|WAITING|" not in row for row in registered.values()):
    raise SystemExit("注册后不是 WAITING（求购被支付撤销了？）：%s" % list(registered.values()))

print("== 6. 把三条任务的 next_fire_time 推到过去（真实等 15min 太久），等一个扫描周期 ==")
# 关单前先记商品的搜索同步消息条数：审核通过 1 条 + 下单锁定 1 条，断言关单只多一条（回在售）
search_before = int(scalar("better_school",
                           "SELECT COUNT(*) FROM outbox_message WHERE topic='bs-search-sync' "
                           "AND JSON_EXTRACT(payload,'$.type')='product' AND JSON_EXTRACT(payload,'$.id')=%d" % product))
for slot in range(16):
    sql("bs_timeout", "UPDATE timeout_task_%02d SET next_fire_time = DATE_SUB(NOW(), INTERVAL 60 SECOND) "
                      "WHERE status IN ('WAITING','RETRYING') AND (task_type,biz_id) IN (%s)" % (slot, in_clause))

final_status = {}
backdated_at = time.time()
deadline = backdated_at + 60
while time.time() < deadline:
    rows = sql("bs_timeout", "SELECT CONCAT(task_type,'|',biz_id,'|',status) FROM ("
               + " UNION ALL ".join(
                   "SELECT task_type,biz_id,status FROM timeout_task_%02d WHERE (task_type,biz_id) IN (%s)"
                   % (slot, in_clause) for slot in range(16)) + ") t")
    final_status = {row.rsplit("|", 1)[0]: row.rsplit("|", 1)[1] for row in rows}
    if len(final_status) == 3 and all(value == "SUCCESS" for value in final_status.values()):
        break
    time.sleep(2)
closure_seconds = round(time.time() - backdated_at, 1)
print("center final: %s（到期 → 三条全 SUCCESS 用时 %.1fs，精度口径 10~20s）" % (final_status, closure_seconds))

product_status = scalar("better_school", "SELECT status FROM product WHERE id = %d" % product)
order_status = scalar("better_school", "SELECT status FROM product_order WHERE id = %d" % order)
errand_status = scalar("better_school", "SELECT status FROM errand_order WHERE id = %d" % errand)
want_status = scalar("better_school", "SELECT status FROM want_to_buy WHERE id = %d" % want)
search_sync = int(scalar("better_school",
                         "SELECT COUNT(*) FROM outbox_message WHERE topic='bs-search-sync' "
                         "AND JSON_EXTRACT(payload,'$.type')='product' AND JSON_EXTRACT(payload,'$.id')=%d" % product))

print("\n== 结果 ==")
print("center:  %s" % final_status)
print("biz:     product_order=%s product=%s" % (order_status, product_status))
print("biz:     errand_order=%s want_to_buy=%s" % (errand_status, want_status))
print("search sync messages for product %d: 关单前 %s -> 关单后 %s（应 +1 = 回在售）" % (product, search_before, search_sync))

problems = []
for task_type, biz_id in KEYS:
    key = "%s|%d" % (task_type, biz_id)
    if final_status.get(key) != "SUCCESS":
        problems.append("中心任务 %s 应为 SUCCESS，实为 %s" % (key, final_status.get(key)))
if order_status != "CANCELLED":
    problems.append("商品订单未被关单：%s" % order_status)
if product_status != "ON_SALE":
    problems.append("商品未回在售：%s" % product_status)
if search_sync != search_before + 1:
    problems.append("关单应补 1 条搜索同步消息（回在售）：关单前 %s、关单后 %s" % (search_before, search_sync))
if errand_status != "CANCELLED":
    problems.append("跑腿单未被关单：%s" % errand_status)
if want_status != "CANCELLED":
    problems.append("求购未被关单：%s" % want_status)

if problems:
    print("\nSMOKE FAILED:")
    for problem in problems:
        print("  - " + problem)
    raise SystemExit(1)

# 断言全过才落这两份产物：smoke11-ids.json 是本轮造的业务 id，smoke11-result.json 是本轮的观测结果
# （落盘时机在断言之后，所以文件存在即代表这一轮跑通，而不是"跑到一半就中断"）
result = {
    "passed": True,
    "closureSeconds": closure_seconds,
    "centerTasks": final_status,
    "business": {
        "product_order": order_status,
        "product": product_status,
        "errand_order": errand_status,
        "want_to_buy": want_status,
        "searchSyncMessages": {"beforeClose": search_before, "afterClose": search_sync},
    },
    "ids": ids,
}
with open(".scratch/timeout-center/smoke11-result.json", "w", encoding="utf-8") as handle:
    json.dump(result, handle, ensure_ascii=True, indent=2)
with open(".scratch/timeout-center/smoke11-ids.json", "w", encoding="utf-8") as handle:
    json.dump(ids, handle, ensure_ascii=False, indent=2)
print("\nSMOKE PASSED")
