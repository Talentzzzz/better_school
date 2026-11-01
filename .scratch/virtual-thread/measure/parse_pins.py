# -*- coding: utf-8 -*-
"""把 `jfr print --events jdk.VirtualThreadPinned` 的文本按「钉住的调用点」聚合。"""
import re
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

MACHINERY = (
    "java.lang.VirtualThread.parkOnCarrierThread",
    "java.lang.VirtualThread.park",
    "java.lang.System$2.parkVirtualThread",
    "jdk.internal.misc.VirtualThreads.park",
    "java.util.concurrent.locks.LockSupport.park",
    "sun.nio.ch.Poller.poll",
    "sun.nio.ch.NioSocketImpl.park",
    "sun.nio.ch.NioSocketImpl.implRead",
    "sun.nio.ch.NioSocketImpl.read",
    "sun.nio.ch.NioSocketImpl$1.read",
    "java.net.Socket$SocketInputStream.read",
    "java.net.SocketInputStream.read",
    "java.io.FilterInputStream.read",
    "java.io.BufferedInputStream",
)

by_site = defaultdict(lambda: [0, 0.0, 0.0])
durations = []
threads = defaultdict(int)

with open("pinned-raw.txt", encoding="utf-8", errors="replace") as f:
    dur = None
    thread = None
    in_stack = False
    site = None
    for line in f:
        s = line.strip()
        if s.startswith("jdk.VirtualThreadPinned {"):
            dur = thread = site = None
            in_stack = False
            continue
        if s.startswith("duration = "):
            dur = float(s.split("=")[1].strip().split()[0])
            continue
        if s.startswith("eventThread = "):
            m = re.search(r'eventThread = "([^"]+)"', s)
            thread = m.group(1) if m else "?"
            continue
        if s.startswith("stackTrace = ["):
            in_stack = True
            continue
        if in_stack:
            if s.startswith("]"):
                in_stack = False
                if dur is not None:
                    durations.append(dur)
                    if thread:
                        threads[re.sub(r"-\d+$", "", thread)] += 1
                    key = site or "(only-jdk-frames)"
                    e = by_site[key]
                    e[0] += 1
                    e[1] += dur
                    e[2] = max(e[2], dur)
                continue
            if site is None:
                frame = s
                if not any(frame.startswith(m) for m in MACHINERY) and not frame.startswith("..."):
                    site = frame

durations.sort()
n = len(durations)


def pct(q):
    return durations[min(n - 1, int(q * (n - 1)))] if n else 0


print("钉住事件总数: %d" % n)
print("时长 ms: p50=%.3f p90=%.3f p99=%.3f max=%.2f 总时长=%.1f s"
      % (pct(0.5), pct(0.9), pct(0.99), durations[-1] if n else 0, sum(durations) / 1000))
for lo, hi in ((0, 0.5), (0.5, 1), (1, 5), (5, 20), (20, 100), (100, 10 ** 9)):
    c = sum(1 for d in durations if lo <= d < hi)
    print("  [%s,%s) ms: %d (%.1f%%)" % (lo, hi if hi < 10 ** 9 else "inf", c, 100.0 * c / max(n, 1)))

print("\n按「第一个非 JDK 内部帧」聚合（前 15）:")
print("%-95s %8s %10s %10s" % ("site", "count", "total_s", "max_ms"))
for site, (c, total, mx) in sorted(by_site.items(), key=lambda kv: -kv[1][1])[:15]:
    print("%-95s %8d %10.2f %10.2f" % (site[:95], c, total / 1000.0, mx))

print("\n按线程名前缀（前 10）:")
for t, c in sorted(threads.items(), key=lambda kv: -kv[1])[:10]:
    print("  %-30s %d" % (t, c))
