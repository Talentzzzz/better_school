# -*- coding: utf-8 -*-
"""生成 results-table.txt（票 04 汇总表）。

直接写文件、不经控制台——Git Bash 控制台是 GBK，管道出去的中文会被写坏。
"""
import io
import json
import os

OUT = "results-table.txt"


def g(tag):
    p = "r-%s.json" % tag
    if not os.path.exists(p):
        return None
    with io.open(p, encoding="utf-8") as f:
        return json.load(f)


def cell(tag):
    r = g(tag)
    if not r:
        return "%-24s" % "-"
    return "%7.1f rps / p50 %5.0fms / err %5.2f%%" % (
        r["rps"], r["overall"]["p50"], r["error_rate"] * 100)


lines = []
lines.append("ticket 04 - monolith better-school, same host, 30s per level, closed-loop mixed load")
lines.append("")
lines.append("== A/B matrix: {VT off,on} x {Hikari 10,64} ==")
lines.append("%-20s %-30s %-30s %-30s" % ("arm", "c=32", "c=128", "c=256"))
for label, tags in (
    ("A VT off pool10", ("A-vtoff-p10-c32", "A-vtoff-p10-c128", "A-vtoff-p10-c256")),
    ("B VT on  pool10", ("B-vton-p10-c32", "B-vton-p10-c128", "B-vton-p10-c256")),
    ("C VT off pool64", ("C-vtoff-p64-c32", "C-vtoff-p64-c128", "C-vtoff-p64-c256")),
    ("D VT on  pool64", ("D-vton-p64-c32", "D-vton-p64-c128", "D-vton-p64-c256")),
):
    lines.append("%-20s %-30s %-30s %-30s" % (label, cell(tags[0]), cell(tags[1]), cell(tags[2])))

lines.append("")
lines.append("== pool / thread accounting (JMX probe, 200ms sampling) ==")
lines.append("%-34s %8s %8s %9s %9s %9s" % ("probe", "act_max", "act_mean", "full_%", "aw_max", "cpu_max"))
import csv
import glob
for f in sorted(glob.glob("probe-*.csv")):
    with io.open(f, encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        continue
    act = [int(r["active"]) for r in rows]
    aw = [int(r["awaiting"]) for r in rows]
    cpu = [float(r["cpu_load"]) for r in rows]
    mx = max(act)
    lines.append("%-34s %8d %8.1f %8.0f%% %9d %9.3f" % (
        f.split("probe-")[1], mx, sum(act) / len(act),
        100.0 * sum(1 for a in act if a >= mx) / len(act), max(aw), max(cpu)))
lines.append("(full_% = share of samples with active == max; aw = ThreadsAwaitingConnection)")

lines.append("")
lines.append("== c=256, single-op: image_upload (MinIO, does not hold a DB connection) ==")
for tag, label in (("AB-a2-upload", "VT off"), ("AB-c-upload", "VT off"),
                   ("AB-b-upload", "VT on"), ("AB-d-upload", "VT on")):
    r = g(tag)
    if r:
        lines.append("  %-16s %-8s rps=%7.1f  p50=%7.0fms  err=%.2f%%" % (
            tag, label, r["rps"], r["overall"]["p50"], r["error_rate"] * 100))

lines.append("")
lines.append("== c=256 mixed, interleaved (ABAB) ==")
lines.append("  NOTE: AB-a was DISCARDED - 34.8% error_rate, all client-side ClientConnectorError,")
lines.append("        and probe-AB-a.csv only got 95 rows (expected ~390). Do not cite it.")
for tag, label in (("AB-a-mixed", "VT off (DISCARDED)"), ("AB-c-mixed", "VT off"),
                   ("AB-b-mixed", "VT on"), ("AB-d-mixed", "VT on")):
    r = g(tag)
    if r:
        lines.append("  %-16s %-18s rps=%7.1f  p50=%7.0fms  p99=%8.0fms  err=%.2f%%" % (
            tag, label, r["rps"], r["overall"]["p50"], r["overall"]["p99"], r["error_rate"] * 100))

lines.append("")
lines.append("== pool=256, c=256 mixed (the one pair where the VT arm never waited for a connection) ==")
for tag, label in (("PS-off-p256", "VT off"), ("PS-on-p256", "VT on")):
    r = g(tag)
    if r:
        lines.append("  %-16s %-8s rps=%7.1f  p50=%7.0fms  p99=%8.0fms  err=%.2f%%" % (
            tag, label, r["rps"], r["overall"]["p50"], r["overall"]["p99"], r["error_rate"] * 100))

lines.append("")
lines.append("== single-op control: post_list only, c=32 (data-growth confound) ==")
r = g("ctrl-postlist-c32")
if r:
    lines.append("  posts ~17k rows: rps=%.1f  p50=%.0fms  (early run at ~500 rows: 1756 rps / 17.7ms, raw file not kept)" % (
        r["rps"], r["overall"]["p50"]))

with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines) + "\n")
print("wrote %s (%d lines)" % (OUT, len(lines)))
