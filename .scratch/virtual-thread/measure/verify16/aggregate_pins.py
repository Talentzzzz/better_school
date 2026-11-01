# -*- coding: utf-8 -*-
"""票 16 终验：把常态化 recording 的 jdk.VirtualThreadPinned 按「第一个非 JDK 内部帧」聚合。

用法（不落中间大文件，直接吃 jfr print 的流）：
  jfr print --events jdk.VirtualThreadPinned --stack-depth 24 final.jfr | python aggregate_pins.py

逻辑照 measure/parse_pins.py，只改成从 stdin 读。
"""
import re
import sys
from collections import defaultdict

sys.stdin.reconfigure(encoding="utf-8", errors="replace")
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

dur = None
thread = None
in_stack = False
site = None

for line in sys.stdin:
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
                by_site[site or "(only-jdk-frames)"][0] += 1
                by_site[site or "(only-jdk-frames)"][1] += dur
                by_site[site or "(only-jdk-frames)"][2] = max(by_site[site or "(only-jdk-frames)"][2], dur)
            continue
        if site is None:
            frame = s
            if not any(frame.startswith(m) for m in MACHINERY) and not frame.startswith("..."):
                site = frame

durations.sort()
n = len(durations)


def pct(q):
    return durations[min(n - 1, int(q * (n - 1)))] if n else 0


print("pinning 事件总数: %d" % n)
print("时长 ms: p50=%.3f p90=%.3f p99=%.3f max=%.2f 总时长=%.1f s"
      % (pct(0.5), pct(0.9), pct(0.99), durations[-1] if n else 0, sum(durations) / 1000))

print("\n按「第一个非 JDK 内部帧」聚合（前 20）:")
print("%-100s %8s %10s %10s" % ("site", "count", "total_s", "max_ms"))
for site_name, (c, total, mx) in sorted(by_site.items(), key=lambda kv: -kv[1][1])[:20]:
    print("%-100s %8d %10.2f %10.2f" % (site_name[:100], c, total / 1000.0, mx))

print("\n按线程名前缀（前 12）:")
for t, c in sorted(threads.items(), key=lambda kv: -kv[1])[:12]:
    print("  %-40s %d" % (t, c))
