# -*- coding: utf-8 -*-
"""票 04 压测客户端：闭循环并发混合负载，覆盖主要阻塞路径。

覆盖：帖子列表/详情/评论列表（Redis + MySQL）、发帖/评论（MySQL + outbox）、
图片上传（MinIO）、二手商品读/发（MySQL）、跑腿下单+支付（MySQL + 钱包 + outbox）。

用法：
  python loadtest.py --seed seed.json --tag baseline --concurrency 128 --duration 30 --out r.json
"""
import argparse
import asyncio
import base64
import json
import random
import sys
import time

import aiohttp

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)

# (名称, 权重)
MIX = [
    ("post_list", 12),
    ("post_detail", 18),
    ("comment_list", 10),
    ("product_list", 8),
    ("product_detail", 8),
    ("post_publish", 10),
    ("comment_add", 12),
    ("image_upload", 6),
    ("product_publish", 6),
    ("errand_create_pay", 10),
]


def chk(payload):
    """返回 (是否成功, 失败备注)。HTTP 200 + 业务码非 200 也算失败。"""
    if not isinstance(payload, dict):
        return False, "non-json"
    if payload.get("code") == "200":
        return True, None
    return False, "biz%s:%s" % (payload.get("code"), str(payload.get("info"))[:60])


async def op_post_list(s, seed):
    async with s.get("/api/community/posts?page=1&pageSize=10") as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_post_detail(s, seed):
    pid = random.choice(seed["postIds"])
    async with s.get("/api/community/posts/%d" % pid) as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_comment_list(s, seed):
    pid = random.choice(seed["postIds"])
    async with s.get("/api/community/posts/%d/comments?page=1&pageSize=10&sort=hot" % pid) as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_product_list(s, seed):
    async with s.get("/api/trade/products?page=1&pageSize=10") as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_product_detail(s, seed):
    pid = random.choice(seed["productIds"])
    async with s.get("/api/trade/products/%d" % pid) as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_post_publish(s, seed):
    body = {"title": "压测帖", "content": "负载测试写入：%d" % random.randint(1, 10 ** 9),
            "category": "DAILY"}
    async with s.post("/api/community/posts", json=body, headers=seed["authorHeaders"]) as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_comment_add(s, seed):
    pid = random.choice(seed["postIds"])
    body = {"content": "压测评论 %d" % random.randint(1, 10 ** 9)}
    async with s.post("/api/community/posts/%d/comments" % pid, json=body,
                      headers=seed["authorHeaders"]) as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_image_upload(s, seed):
    form = aiohttp.FormData()
    form.add_field("file", PNG, filename="lt.png", content_type="image/png")
    async with s.post("/api/files/upload", data=form, headers=seed["authorHeaders"]) as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_product_publish(s, seed):
    body = {"title": "压测商品", "description": "负载测试写入", "price": 12.5,
            "condition": "LIKE_NEW", "tradeLocation": "6号楼下", "category": "DAILY"}
    async with s.post("/api/trade/products", json=body, headers=seed["authorHeaders"]) as r:
        return (r.status,) + chk(await r.json(content_type=None))


async def op_errand_create_pay(s, seed):
    body = {"type": "EXPRESS_PICKUP", "description": "压测跑腿单",
            "pickupLocation": "菜鸟驿站", "deliveryLocation": "6号楼下",
            "reward": 10, "timeLimit": "HOURS_2"}
    async with s.post("/api/errand/orders", json=body, headers=seed["authorHeaders"]) as r:
        payload = await r.json(content_type=None)
        st = r.status
    ok, note = chk(payload)
    if not ok:
        return st, False, "create:" + str(note)
    oid = payload["data"]
    async with s.post("/api/errand/orders/%d/pay" % oid, headers=seed["authorHeaders"]) as r:
        return (r.status,) + chk(await r.json(content_type=None))


OPS = {
    "post_list": op_post_list, "post_detail": op_post_detail, "comment_list": op_comment_list,
    "product_list": op_product_list, "product_detail": op_product_detail,
    "post_publish": op_post_publish, "comment_add": op_comment_add,
    "image_upload": op_image_upload, "product_publish": op_product_publish,
    "errand_create_pay": op_errand_create_pay,
}

NAMES = [n for n, _ in MIX]
WEIGHTS = [w for _, w in MIX]


def apply_only_op(name):
    """诊断用：把负载收窄到单个 op，用来定位瓶颈在哪条路径上。"""
    global NAMES, WEIGHTS
    assert name in OPS, "unknown op " + name
    NAMES = [name]
    WEIGHTS = [1]


def pct(sorted_vals, q):
    if not sorted_vals:
        return None
    idx = min(len(sorted_vals) - 1, int(round(q * (len(sorted_vals) - 1))))
    return round(sorted_vals[idx], 2)


async def worker(wid, session, seed, deadline):
    rnd = random.Random(1000 + wid)
    local = []
    while time.perf_counter() < deadline:
        name = NAMES[rnd.choices(range(len(NAMES)), weights=WEIGHTS)[0]]
        t0 = time.perf_counter()
        try:
            status, ok, note = await OPS[name](session, seed)
        except Exception as e:  # 连接级失败也要计入
            status, ok, note = 0, False, type(e).__name__
        err = note if not ok else None
        local.append((name, (time.perf_counter() - t0) * 1000.0, status, ok, err))
    return local


async def run(args, seed):
    seed["authorHeaders"] = {"Authorization": "Bearer " + seed["authorToken"]}
    timeout = aiohttp.ClientTimeout(total=60)
    connector = aiohttp.TCPConnector(limit=0, limit_per_host=0, ttl_dns_cache=300)
    async with aiohttp.ClientSession(base_url=args.base, timeout=timeout, connector=connector) as session:
        if args.warmup > 0:
            warm_deadline = time.perf_counter() + args.warmup
            await asyncio.gather(*[worker(900 + i, session, seed, warm_deadline)
                                   for i in range(min(args.concurrency, 32))])
            print("warmup done (%ss)" % args.warmup)

        t_start = time.perf_counter()
        deadline = t_start + args.duration
        batches = await asyncio.gather(*[worker(i, session, seed, deadline)
                                         for i in range(args.concurrency)])
        t_end = time.perf_counter()
    samples = [x for batch in batches for x in batch]

    wall = t_end - t_start
    per_op = {}
    for name, dt, status, ok, err in samples:
        d = per_op.setdefault(name, {"lat": [], "ok": 0, "err": 0, "statuses": {}, "errors": {}})
        d["lat"].append(dt)
        if ok:
            d["ok"] += 1
        else:
            d["err"] += 1
            if status != 200:
                d["statuses"][str(status)] = d["statuses"].get(str(status), 0) + 1
            if err:
                d["errors"][err] = d["errors"].get(err, 0) + 1

    report = {"tag": args.tag, "concurrency": args.concurrency, "duration_sec": round(wall, 2),
              "total_requests": len(samples), "rps": round(len(samples) / wall, 1),
              "error_rate": round(sum(1 for x in samples if not x[3]) / max(len(samples), 1), 5),
              "ops": {}}
    for name, d in per_op.items():
        lat = sorted(d["lat"])
        report["ops"][name] = {
            "count": len(lat), "rps": round(len(lat) / wall, 1),
            "ok": d["ok"], "err": d["err"],
            "p50": pct(lat, 0.50), "p90": pct(lat, 0.90), "p99": pct(lat, 0.99),
            "max": round(lat[-1], 2), "mean": round(sum(lat) / len(lat), 2),
            "err_statuses": d["statuses"], "err_types": d["errors"],
        }
    all_lat = sorted(x[1] for x in samples)
    report["overall"] = {"p50": pct(all_lat, 0.50), "p90": pct(all_lat, 0.90),
                         "p99": pct(all_lat, 0.99), "max": round(all_lat[-1], 2)}
    return report


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--seed", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--concurrency", type=int, default=128)
    ap.add_argument("--duration", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=5)
    ap.add_argument("--only-op", default=None, help="诊断用：只跑单个 op")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if args.only_op:
        apply_only_op(args.only_op)

    with open(args.seed, encoding="utf-8") as f:
        seed = json.load(f)
    report = asyncio.run(run(args, seed))
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(json.dumps({k: report[k] for k in ("tag", "concurrency", "total_requests", "rps",
                                             "error_rate", "overall")}, ensure_ascii=False))
    for name, d in sorted(report["ops"].items()):
        print("  %-20s n=%-7d rps=%-8.1f p50=%-8.2f p99=%-9.2f err=%d %s"
              % (name, d["count"], d["rps"], d["p50"], d["p99"], d["err"],
                 (d["err_statuses"] or d["err_types"] or "")))


if __name__ == "__main__":
    main()
