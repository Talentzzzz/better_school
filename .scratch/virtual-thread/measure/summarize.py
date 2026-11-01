# -*- coding: utf-8 -*-
"""把 measure/ 下的 r-*.json 汇总成对照表（票 04 结论用）。"""
import glob
import json
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PAT = re.compile(r"^r-(?P<tag>.+)-c(?P<c>\d+)\.json$")


def load():
    rows = []
    for path in sorted(glob.glob("r-*.json")):
        m = PAT.match(path.split("/")[-1].split("\\")[-1])
        if not m:
            continue
        with open(path, encoding="utf-8") as f:
            r = json.load(f)
        rows.append((m.group("tag"), int(m.group("c")), r))
    return rows


def main():
    rows = load()
    if not rows:
        print("no r-*.json")
        return
    print("== 总体（按臂 × 并发） ==")
    print("%-16s %5s %10s %9s %9s %9s %9s" % ("arm", "conc", "rps", "p50", "p90", "p99", "err_rate"))
    for tag, c, r in rows:
        o = r["overall"]
        print("%-16s %5d %10.1f %9.2f %9.2f %9.2f %9.5f"
              % (tag, c, r["rps"], o["p50"], o["p90"], o["p99"], r["error_rate"]))

    ops = sorted({op for _, _, r in rows for op in r["ops"]})
    for op in ops:
        print("\n== %s ==" % op)
        print("%-16s %5s %10s %9s %9s %9s %8s" % ("arm", "conc", "rps", "p50", "p90", "p99", "err"))
        for tag, c, r in rows:
            d = r["ops"].get(op)
            if not d:
                continue
            print("%-16s %5d %10.1f %9.2f %9.2f %9.2f %8d"
                  % (tag, c, d["rps"], d["p50"], d["p90"], d["p99"], d["err"]))


if __name__ == "__main__":
    main()
