# -*- coding: utf-8 -*-
"""对账 docs/db/*.md 的表结构文档与线上库 schema：
1) 每张活表是否被某篇文档收录（按 `# <table>（...` 或 `# <table>` 标题识别）
2) 文档里每张表列出的字段名集合/顺序是否与线上库一致
3) 文档里是否出现已删表名
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "backend", "better-school", "docs", "db")
COLUMNS_TSV = os.path.join(ROOT, ".scratch", "project-rework", "dev_schema_columns.tsv")

DROPPED = ["want_to_buy", "errand_order_member", "agent_review_log", "errand_evaluation",
           "user_daily_active", "delay_outbox_message", "room_user", "wallet_transaction",
           "user_credit_log"]

# 表标题两种形态：`# outbox_message（本地消息表…）` / `# post`；
# 排除「# chat 域表（…）」这类域总览标题（票 18 修正的误报）
HEADING = re.compile(r"^#\s+([a-z_][a-z0-9_]*)（.*$|^#\s+([a-z_][a-z0-9_]*)\s*$", re.M)
ROW = re.compile(r"^\|([a-z_][a-z0-9_]*)\|", re.M)


def live_columns():
    """{table: [col, ...]} 按 ORDINAL_POSITION 顺序"""
    live = {}
    with open(COLUMNS_TSV, encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            live.setdefault(parts[0], []).append(parts[1])
    return live


def doc_tables():
    """{table: (filename, [col, ...])}：每个 `# table` 标题到下一个 `#` 标题之间的表格行"""
    found = {}
    for fn in sorted(os.listdir(DOCS)):
        if not fn.endswith(".md") or fn == "README.md":
            continue
        with open(os.path.join(DOCS, fn), encoding="utf-8") as fh:
            text = fh.read()
        heads = list(HEADING.finditer(text))
        for i, h in enumerate(heads):
            table = h.group(1) or h.group(2)
            end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
            body = text[h.end():end]
            cols = ROW.findall(body)
            if table in found:
                print(f"  [重复收录] {table}: {found[table][0]} 与 {fn}")
            found[table] = (fn, cols)
    return found


def main():
    live = live_columns()
    docs = doc_tables()
    bad = 0

    print("== 未被任何 db 文档收录的活表 ==")
    missing = [t for t in sorted(live) if t not in docs]
    print("  " + (", ".join(missing) if missing else "（无）"))
    bad += len(missing)

    print("\n== 文档中没有对应活表的表名（幽灵表） ==")
    ghosts = [t for t in sorted(docs) if t not in live]
    for t in ghosts:
        print(f"  {t}  (in {docs[t][0]})")
    if not ghosts:
        print("  （无）")
    bad += len(ghosts)

    print("\n== 字段集合/顺序不一致的表 ==")
    any_diff = False
    for t in sorted(set(live) & set(docs)):
        fn, doc_cols = docs[t]
        if doc_cols != live[t]:
            any_diff = True
            bad += 1
            only_doc = [c for c in doc_cols if c not in live[t]]
            only_live = [c for c in live[t] if c not in doc_cols]
            print(f"  {t} ({fn})")
            if only_doc:
                print(f"      文档多出: {only_doc}")
            if only_live:
                print(f"      文档缺少: {only_live}")
            if not only_doc and not only_live:
                print(f"      顺序不同: doc={doc_cols}")
    if not any_diff:
        print("  （无）")

    print("\n== 已删表名残留 ==")
    residue = False
    for fn in sorted(os.listdir(DOCS)):
        if not fn.endswith(".md"):
            continue
        with open(os.path.join(DOCS, fn), encoding="utf-8") as fh:
            text = fh.read()
        for name in DROPPED:
            for m in re.finditer(r"\b" + re.escape(name) + r"\b", text):
                line_no = text[:m.start()].count("\n") + 1
                line = text.splitlines()[line_no - 1].strip()
                # 明确标注「已删/已改名/不再存在」的行放行
                if any(k in line for k in ("已删", "已改名", "改名", "不再", "无此表", "已下线", "旧链路")):
                    continue
                residue = True
                print(f"  {fn}:{line_no}  {name}  →  {line[:110]}")
    if not residue:
        print("  （无未标注的残留）")

    print(f"\n问题数：{bad}（另见上面的已删表残留）")
    return 1 if bad or residue else 0


if __name__ == "__main__":
    sys.exit(main())
