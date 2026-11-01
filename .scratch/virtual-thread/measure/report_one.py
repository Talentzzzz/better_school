import json, sys
tag = sys.argv[1]
for k in ("mixed", "upload"):
    try:
        r = json.load(open("r-%s-%s.json" % (tag, k), encoding="utf-8"))
    except FileNotFoundError:
        continue
    print("%-8s %-7s rps=%-7.1f p50=%-8.2f p99=%-9.2f err=%.4f"
          % (tag, k, r["rps"], r["overall"]["p50"], r["overall"]["p99"], r["error_rate"]))
