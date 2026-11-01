#!/usr/bin/env python3
"""票 12 仿真：速率租约口径下的突发上界 / 窗口准确度 / 收敛速度。

模型（对齐票 04/05/06/08 的口径）：
- 中心单 key 账本 = 每 (key, 实例) 一条 (share, wants, expiry)，ttl = 2T，过期清理。
- 分配 = Doorman ProportionalShare 逐字语义（algorithm.go:213-292）：
    equalShare = R / count
    unused     = R - SumHas() + old.Has      # 排除请求者自身
    若 SumWants <= R 或 wants <= equalShare: gets = min(wants, unused)
    否则 gets = equalShare + (wants-equalShare)*(extraCapacity/extraNeed)
         gets = min(gets, unused)
- 客户端本地桶：rate = share，容量 = share * burstSeconds，
  份额变化时存量按容量比例缩放（票 04 Q9，Guava doSetRate 形状）。
- 续租周期 T + 抖动 U(0, 0.1T)；断租 = fail-open 继续最后份额。
- wants = (allowed + denied) / elapsed（票 04 Q4：上报计数、进公式前换算速率）。

用法：python sim_rate_lease.py [场景名 ...]   # 无参 = 全部
输出：measure/results/r-<场景>.json
"""
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))


class Sim:
    def __init__(self, name, R, N, duration, dt, burst=1.0, T=5.0, ttl_mult=2.0,
                 offered_fn=None, partition=None, allocation="proportional",
                 first_lease_window=0.1, cap_floor=0.0, seed=7):
        self.name, self.R, self.N = name, R, N
        self.duration, self.dt, self.burst = duration, dt, burst
        self.cap_floor = cap_floor  # 容量下限（个请求）：对照组用 1.0 测"份额<1/burst"边界
        self.T, self.ttl = T, ttl_mult * T
        self.offered_fn = offered_fn or (lambda t: np.full(N, R / N))
        self.partition = partition or (lambda t: np.zeros(N, dtype=bool))
        self.allocation = allocation
        rng = np.random.default_rng(seed)
        self.rng = rng
        # 客户端状态
        self.share = np.zeros(N)                # 生效份额（本地桶速率）
        self.tokens = np.zeros(N)               # 本地桶水位
        self.next_renew = rng.uniform(0, first_lease_window, N)  # 首租合批窗内
        self.allowed_since = np.zeros(N)
        self.denied_since = np.zeros(N)
        self.last_report = np.full(N, -1.0)
        self.registered = np.zeros(N, dtype=bool)  # 已首租
        # 中心账本
        self.has = np.zeros(N)                  # 账本份额
        self.wants = np.zeros(N)                # 账本最近上报 wants
        self.expiry = np.full(N, -1.0)
        self.in_ledger = np.zeros(N, dtype=bool)
        # 观测
        self.t = 0.0
        self.series_t = []
        self.series_admit = []                  # 每 tick 放行量
        self.series_sum_share = []
        self.series_sum_tokens = []
        self.alloc_count = 0                    # 分配次数（CPU 代理①）
        self.scan_cost = 0                      # O(n) 扫过的条目数（CPU 代理②）
        self.hot = None                         # 翻转场景的焦点实例

    # ---- 中心：单 key 分配（Doorman ProportionalShare 逐字） ----
    def allocate(self, i, wants, now):
        R = self.R
        self.scan_cost += int(self.in_ledger.sum()) + 1
        self.alloc_count += 1
        old_has = self.has[i] if self.in_ledger[i] else 0.0
        if self.allocation == "static":
            # 对照组：Sentinel AVG_LOCAL 形状 = 静态 R/N（活跃实例数固定为 N）
            gets = min(wants, R / self.N)
        else:
            count = int(self.in_ledger.sum()) + (0 if self.in_ledger[i] else 1)
            equal_share = R / count
            unused = R - self.has[self.in_ledger].sum() + old_has
            sum_wants = self.wants[self.in_ledger].sum()
            if (not self.in_ledger[i]) or sum_wants <= R or wants <= equal_share:
                gets = min(wants, unused)
            else:
                extra_cap = 0.0
                extra_need = 0.0
                idx = np.where(self.in_ledger)[0]
                ws = self.wants[idx].copy()
                ws[idx == i] = wants  # 请求者用本轮 wants（algorithm.go:262-271）
                over = ws[ws > equal_share]
                extra_need = float((over - equal_share).sum())
                under = ws[ws < equal_share]
                extra_cap = float((equal_share - under).sum())
                if extra_need <= 0:
                    gets = min(wants, unused)
                else:
                    gets = equal_share + (wants - equal_share) * (extra_cap / extra_need)
                    gets = min(gets, unused)
        # 账本落账
        self.has[i] = gets
        self.wants[i] = wants
        self.expiry[i] = now + self.ttl
        self.in_ledger[i] = True
        return gets

    # ---- 客户端：份额落地（rescale）+ 续租 ----
    def apply_share(self, i, new_share):
        old = self.share[i]
        old_cap = max(old * self.burst, self.cap_floor)
        new_cap = max(new_share * self.burst, self.cap_floor)
        if old_cap > 0:
            self.tokens[i] *= new_cap / old_cap  # 存量按容量比例缩放（票 04 Q9）
        else:
            self.tokens[i] = 0.0
        self.share[i] = new_share

    def renew(self, i, now):
        elapsed = now - self.last_report[i]
        wants = (self.allowed_since[i] + self.denied_since[i]) / elapsed if elapsed > 0 else self.wants[i]
        if self.partition(now)[i]:
            # 续租失败：fail-open 继续最后份额，账本不动（本轮不重置计数器）
            self.next_renew[i] = now + self.T * (1 + self.rng.uniform(0, 0.1))
            return
        gets = self.allocate(i, wants, now)
        self.apply_share(i, gets)
        self.allowed_since[i] = 0.0
        self.denied_since[i] = 0.0
        self.last_report[i] = now
        self.registered[i] = True
        self.next_renew[i] = now + self.T * (1 + self.rng.uniform(0, 0.1))

    def step(self):
        dt, t = self.dt, self.t
        # 中心：过期清理（先清再分配，Doorman Clean() 语义）
        self.in_ledger &= self.expiry > t
        # 客户端续租到期者
        due = np.where(t >= self.next_renew)[0]
        for i in due:
            self.renew(i, t)
        # 流量与本地桶
        offered_rate = self.offered_fn(t)
        arrivals = self.rng.poisson(offered_rate * dt)
        cap = np.maximum(self.share * self.burst, self.cap_floor)
        self.tokens = np.minimum(cap, self.tokens + self.share * dt)
        # 请求是整数：tokens < 1 时该请求被拒（Guava tryAcquire 语义）
        admit = np.minimum(arrivals, np.floor(self.tokens + 1e-9))
        self.tokens -= admit
        self.allowed_since += admit
        self.denied_since += arrivals - admit
        # 观测
        self.series_t.append(t)
        self.series_admit.append(float(admit.sum()))
        self.series_sum_share.append(float(self.share.sum()))
        self.series_sum_tokens.append(float(self.tokens.sum()))
        self.t = t + dt

    def run(self):
        n_ticks = int(self.duration / self.dt)
        for _ in range(n_ticks):
            self.step()
        return self

    # ---- 读数 ----
    def windows(self, deltas):
        admit = np.array(self.series_admit)
        dt = self.dt
        out = {}
        for d in deltas:
            d = float(d)
            w = max(1, int(round(d / dt)))
            if w > len(admit):
                continue
            # 滑动窗口平均放行率的最坏值（max over all offsets）
            c = np.cumsum(np.concatenate([[0.0], admit]))
            rates = (c[w:] - c[:-w]) / (w * dt)
            out[str(d)] = float(rates.max())
        return out

    def summary(self):
        admit = np.array(self.series_admit)
        dt = self.dt
        cum = np.cumsum(admit)
        s = {
            "name": self.name,
            "R": self.R, "N": self.N, "T": self.T, "ttl": self.ttl,
            "burstSeconds": self.burst, "duration_s": self.duration, "dt": self.dt,
            "total_admitted": float(cum[-1]),
            "long_run_rate": float(cum[-1] / self.duration),
            "max_100ms_rate": self.windows([0.1])["0.1"],
            "window_worst_rates": (ww := self.windows([1, 5, 10, 30, 60, 120])),
            "max_1s_rate": ww.get("1.0"),
            "max_sum_share": float(max(self.series_sum_share)),
            "max_sum_share_over_R": float(max(self.series_sum_share) / self.R),
            "max_sum_tokens": float(max(self.series_sum_tokens)),
            "alloc_count": self.alloc_count,
            "scan_cost": self.scan_cost,
        }
        if self.hot is not None:
            s["hot_share_trace"] = self.hot
        return s


def flip_offered(base, hot_rate, flip_t, hot_idx, N):
    def fn(t):
        r = np.full(N, base)
        if t >= flip_t:
            r[hot_idx] = hot_rate
        return r
    return fn


def run_all(names):
    results = {}

    # S1 稳态均衡：N=200，每实例 5/s（合计恰好 R），跑 300s
    if "s1" in names:
        sim = Sim("s1-steady", R=1000, N=200, duration=300, dt=0.01,
                  offered_fn=lambda t: np.full(200, 5.0)).run()
        results["s1"] = sim.summary()

    # S2 流量翻转：N=200，冷实例 2/s；t=100s 实例 0 跳到 1000/s（需求 1198 > R）
    if "s2" in names:
        sim = Sim("s2-flip", R=1000, N=200, duration=200, dt=0.01,
                  offered_fn=flip_offered(2.0, 1000.0, 100.0, 0, 200))
        sim.hot = []
        hot_t, hot_share = [], []
        n_ticks = int(sim.duration / sim.dt)
        for k in range(n_ticks):
            sim.step()
            if k % 100 == 0:  # 每 1s 采样
                hot_t.append(sim.t)
                hot_share.append(float(sim.share[0]))
        s = sim.summary()
        s["hot_share_timeline"] = {"t": hot_t, "share": hot_share}
        # 收敛读数：翻转后实例 0 份额达到终值 95% 的时刻、全局放行率回到 R(1±2%) 的时刻
        final = hot_share[-1]
        conv_share = next((t for t, v in zip(hot_t, hot_share) if t > 100 and v >= 0.95 * final), None)
        admit = np.array(sim.series_admit)
        dt = sim.dt
        w = int(1.0 / dt)
        c = np.cumsum(np.concatenate([[0.0], admit]))
        rates = (c[w:] - c[:-w]) / (w * dt)
        ts = np.arange(len(rates)) * dt
        conv_rate = next((float(t) for t, rr in zip(ts, rates) if t > 100.5 and 0.98 * sim.R <= rr <= 1.02 * sim.R), None)
        s["convergence_share_95pct_s"] = conv_share
        s["convergence_rate_2pct_s"] = conv_rate
        results["s2"] = s

    # S2b 对照：同场景、静态 R/N 分配（Sentinel AVG_LOCAL 形状）——看倾斜流量饿死
    if "s2b" in names:
        sim = Sim("s2b-static", R=1000, N=200, duration=200, dt=0.01,
                  offered_fn=flip_offered(2.0, 1000.0, 100.0, 0, 200),
                  allocation="static")
        sim.hot = []
        hot_t, hot_share = [], []
        n_ticks = int(sim.duration / sim.dt)
        for k in range(n_ticks):
            sim.step()
            if k % 100 == 0:
                hot_t.append(sim.t)
                hot_share.append(float(sim.share[0]))
        s = sim.summary()
        s["hot_share_timeline"] = {"t": hot_t, "share": hot_share}
        s["hot_admit_rate_last30s"] = float(np.sum(sim.series_admit[-3000:]) / 30.0)
        results["s2b"] = s

    # S3 栅栏突发：N=200，先 20s 稳态 5/s（桶充满），t=20s 全体同步 10ms 内打 20 个
    if "s3" in names:
        def barrier(t):
            r = np.full(200, 5.0)
            if 20.0 <= t < 20.01:
                r[:] = 2000.0
            return r
        sim = Sim("s3-coldstart", R=1000, N=200, duration=30, dt=0.001,
                  offered_fn=barrier).run()
        s = sim.summary()
        i0 = int(20.0 / sim.dt)
        s["burst_admit_100ms_after_t20"] = float(np.sum(sim.series_admit[i0:i0 + 100]))
        s["burst_bound_window"] = 1000 * (0.1 + 1.0)  # R*(Δ+burstSeconds)
        s["max_tokens_at_burst"] = float(max(sim.series_sum_tokens[i0 - 10:i0 + 100]))
        s["instant_burst_bound"] = 1000 * 1.0
        results["s3"] = s

    # S4 断租 fail-open：N=200 全饱和（每实例 offered 1000/s）；t=100s 起 20 实例断租 40s（> ttl=10s）
    # 陈旧份额（断租实例继续按最后份额跑）+ 账本过期后再分配 → Σ生效份额 > R 的唯一通道
    if "s4" in names:
        def part(t):
            p = np.zeros(200, dtype=bool)
            if 100.0 <= t < 140.0:
                p[:20] = True
            return p
        sim = Sim("s4-failopen", R=1000, N=200, duration=200, dt=0.01,
                  offered_fn=lambda t: np.full(200, 1000.0), partition=part).run()
        s = sim.summary()
        dt = sim.dt
        # 分段读数
        def seg(a, b):
            i0, i1 = int(a / dt), int(b / dt)
            c = np.cumsum(np.concatenate([[0.0], np.array(sim.series_admit)]))
            return float((c[i1] - c[i0]) / (b - a))
        s["rate_pre_100s"] = seg(50, 100)
        s["rate_degraded_115_140s"] = seg(115, 140)  # 条目已过期、份额已再分配
        s["rate_recovered_150_200s"] = seg(150, 200)
        s["max_sum_share_degraded"] = float(max(sim.series_sum_share[int(100 / dt):int(140 / dt)]))
        s["max_1s_rate_degraded"] = max(
            np.convolve(np.array(sim.series_admit), np.ones(100) / (100 * dt), "valid")[
                int(100 / dt):int(140 / dt)].max(), 0.0)
        results["s4"] = s

    # S5 大 N：N=5000，冷实例 0.19/s；t=50s 实例 0 跳到 900/s
    if "s5" in names:
        sim = Sim("s5-largeN", R=1000, N=5000, duration=120, dt=0.05,
                  offered_fn=flip_offered(0.19, 900.0, 50.0, 0, 5000))
        sim.hot = []
        hot_t, hot_share = [], []
        n_ticks = int(sim.duration / sim.dt)
        for k in range(n_ticks):
            sim.step()
            if k % 20 == 0:  # 每 1s
                hot_t.append(sim.t)
                hot_share.append(float(sim.share[0]))
        s = sim.summary()
        s["hot_share_timeline"] = {"t": hot_t, "share": hot_share}
        final = hot_share[-1]
        s["convergence_share_95pct_s"] = next(
            (t for t, v in zip(hot_t, hot_share) if t > 50 and v >= 0.95 * final), None)
        # 份额分布读数：share×burst < 1 的实例占比（这些实例 tryAcquire 恒拒）
        sh = sim.share
        s["frac_share_below_1req"] = float((sh * sim.burst < 1.0).mean())
        # 账本塌缩读数（§8 闲置清零：零到达轮 wants=0 ⇒ 份额清零）
        led = sim.has[1:]
        s["ledger_sum_excl_hot_end"] = float(led.sum())
        s["frac_ledger_zero_end"] = float((led == 0).mean())
        results["s5"] = s

    # S5b 对照：同场景、本地桶容量下限 = 1 个请求（测"份额<1/burst"边界的缓解）
    if "s5b" in names:
        sim = Sim("s5b-capfloor", R=1000, N=5000, duration=120, dt=0.05,
                  offered_fn=flip_offered(0.19, 900.0, 50.0, 0, 5000),
                  cap_floor=1.0).run()
        s = sim.summary()
        s["cap_floor"] = 1.0
        results["s5b"] = s

    # S3b 紧致性：稳态 → 需求塌缩 2s（<1 个续租周期）→ 全体同步突发
    if "s3b" in names:
        def collapse_surge(t):
            r = np.full(200, 5.0)
            if 20.0 <= t < 22.0:
                r[:] = 0.0
            elif 22.0 <= t < 22.01:
                r[:] = 2000.0
            return r
        sim = Sim("s3b-collapsesurge", R=1000, N=200, duration=40, dt=0.001,
                  offered_fn=collapse_surge).run()
        s = sim.summary()
        i0 = int(22.0 / sim.dt)
        s["burst_admit_100ms_after_t22"] = float(np.sum(sim.series_admit[i0:i0 + 100]))
        s["burst_bound_window"] = 1000 * (0.1 + 1.0)
        s["max_tokens_after_collapse"] = float(max(sim.series_sum_tokens[int(20 / sim.dt):i0 + 100]))
        results["s3b"] = s

    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    for k, v in results.items():
        p = os.path.join(HERE, "results", f"r-{k}.json")
        with open(p, "w", encoding="utf-8") as f:
            json.dump(v, f, ensure_ascii=False, indent=1)
        print(f"wrote {p}")
    return results


if __name__ == "__main__":
    names = set(sys.argv[1:]) or {"s1", "s2", "s2b", "s3", "s3b", "s4", "s5", "s5b"}
    res = run_all(names)
    for k, v in res.items():
        print(f"\n== {k} ==")
        for key in ("long_run_rate", "max_1s_rate", "max_100ms_rate",
                    "max_sum_share_over_R", "convergence_share_95pct_s",
                    "convergence_rate_2pct_s", "rate_degraded_115_140s",
                    "hot_admit_rate_last30s",
                    "frac_share_below_1req", "ledger_sum_excl_hot_end",
                    "frac_ledger_zero_end"):
            if key in v:
                print(f"  {key}: {v[key]}")
        if "window_worst_rates" in v:
            print("  window_worst_rates:", {k2: round(vv, 1) for k2, vv in v["window_worst_rates"].items()})
