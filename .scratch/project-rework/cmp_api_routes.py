# -*- coding: utf-8 -*-
"""对账 Controller 的路由与 docs/api/*.md 里写的路径：
报「代码里有、文档没写」和「文档里有、代码里没有」两类偏差。

口径（票 18 收口时修正）：
- 文档按「README 总览表声明 模块前缀 → 文档文件」解析：文档正文写的是相对路径
  （`### GET /rooms`），比对时按所属模块前缀拼全；README 没声明前缀的控制器
  （如 credit / search）按「全路径字面量必须出现在任何文档里」兜底比对。
- 控制器注解支持带附加属性（`@PostMapping(value="/upload", consumes=...)`）。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MOD = os.path.join(ROOT, "backend", "better-school")
CTRL_DIR = os.path.join(MOD, "bs-trigger", "src", "main", "java", "com", "betterschool", "trigger", "http")
DOCS_DIR = os.path.join(MOD, "docs", "api")

CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*"([^"]+)"')
METHOD_MAPPING = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\s*\(([^)]*)\)')
MAPPING_NOARG = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*\))?\s*$')
QUOTED = re.compile(r'"([^"]*)"')
# README 总览表行：| 模块 | [doc.md](doc.md) | /api/prefix |
README_ROW = re.compile(r"^\|.*?\|\s*\[([^\]]+\.md)\]\([^)]+\)\s*\|\s*`?(\/api\/[a-z0-9\-]+)`?\s*\|", re.M)
HEADING_ROUTE = re.compile(r"^#{2,4}\s*(GET|POST|PUT|DELETE|PATCH)\s+(/\S*)\s*$", re.M)


def routes_of(path):
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    base_m = CLASS_MAPPING.search(src)
    base = base_m.group(1) if base_m else ""
    routes = []
    for line in src.splitlines():
        m = METHOD_MAPPING.search(line)
        if m:
            verb = m.group(1).upper()
            sub = QUOTED.search(m.group(2) or "")
            sub = sub.group(1) if sub else ""
            if sub.startswith("/"):
                routes.append((verb, base + sub))
            elif not (m.group(2) or "").strip():
                routes.append((verb, base))
        elif MAPPING_NOARG.search(line.strip()):
            verb = MAPPING_NOARG.search(line.strip()).group(1).upper()
            routes.append((verb, base))
    return routes


def normalize(p):
    # 文档常用 {orderId}，代码同形；只去尾斜杠与查询串
    return p.split("?")[0].rstrip("/")


def main():
    docs = {}
    for fn in os.listdir(DOCS_DIR):
        if fn.endswith(".md"):
            with open(os.path.join(DOCS_DIR, fn), encoding="utf-8") as fh:
                docs[fn] = fh.read()

    # 前缀 → 文档文件（README 总览表）
    readme = docs.get("README.md", "")
    prefix_doc = {}
    for m in README_ROW.finditer(readme):
        prefix_doc[m.group(2).rstrip("/")] = m.group(1)

    all_doc_text = "\n".join(docs.values())
    missing_in_docs, missing_in_code = [], []

    for fn in sorted(os.listdir(CTRL_DIR)):
        if not fn.endswith("Controller.java"):
            continue
        for verb, route in routes_of(os.path.join(CTRL_DIR, fn)):
            route = normalize(route)
            prefix = "/".join(route.split("/")[:3])  # /api/<module>
            doc_fn = prefix_doc.get(prefix)
            if doc_fn:
                sub = route[len(prefix):] or "/"
                text = docs.get(doc_fn, "")
                # 文档允许 `.../后缀` 连写简写（如 `POST .../sold`）
                segs = [s for s in sub.split("/") if s]
                shorthand = [".../" + "/".join(segs[i:]) for i in range(1, len(segs))]
                if sub not in text and not any(sh in text for sh in shorthand):
                    missing_in_docs.append((fn, verb, route))
            elif route not in all_doc_text:
                missing_in_docs.append((fn, verb, route))

    # 文档里出现、但没有任何 controller 提供的路径
    code_paths = set()
    for fn in os.listdir(CTRL_DIR):
        if fn.endswith("Controller.java"):
            for _, route in routes_of(os.path.join(CTRL_DIR, fn)):
                code_paths.add(normalize(route))

    def known(p):
        if p in code_paths:
            return True
        # 允许前缀式匹配（文档里常写连写系列）
        return any(cp.startswith(p) or p.startswith(cp) for cp in code_paths)

    # 1) 全路径字面量（README / 正文里写全的）
    doc_path_re = re.compile(r"/api/[a-zA-Z0-9_\-{}/]+")
    for doc_fn, text in docs.items():
        for raw in doc_path_re.findall(text):
            p = normalize(raw)
            if p.endswith(("/**", "/*")) or known(p):
                continue
            if p.startswith("/api/chat/ws/"):
                continue  # WS 握手 URL（GET /ws/chat?ticket=），非 HTTP 控制器路由
            missing_in_code.append((doc_fn, p))

    # 2) 模块文档里的相对路径标题（### GET /rooms）按前缀拼全后比对
    for m in HEADING_ROUTE.finditer(all_doc_text):
        verb, sub = m.group(1), normalize(m.group(2))
        if sub.startswith("/api/") or sub.startswith("/ws"):
            # 全路径另行处理；/ws/** 是 WebSocket 握手 URL，非 HTTP 控制器路由
            continue
        # 找到包含该标题的文档文件，取它声明的前缀
        doc_fn = next((fn for fn, t in docs.items() if m.group(0) in t), None)
        prefix = next((p for p, d in prefix_doc.items() if d == doc_fn), None)
        if prefix is None:
            continue
        full = normalize(prefix + sub)
        if not known(full):
            missing_in_code.append((doc_fn, full))

    # 去重保序
    missing_in_code = list(dict.fromkeys(missing_in_code))

    print("== 代码有路由、文档没提 ==")
    for fn, verb, route in missing_in_docs:
        print(f"  {fn:32s} {verb:6s} {route}")
    if not missing_in_docs:
        print("  （无）")
    print("\n== 文档有路径、代码里没有 ==")
    for fn, p in missing_in_code:
        print(f"  {fn:20s} {p}")
    if not missing_in_code:
        print("  （无）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
