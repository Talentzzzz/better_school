# -*- coding: utf-8 -*-
"""对账 DAO 接口方法的 @Param 名与 mapper XML 里的 #{...} 占位符：
报「占位符引用了未声明的参数名」这一类的潜在运行时 BindingException。
只做保守检查：方法有 @Param 时，占位符名必须落在声明集合里；单参无 @Param 时跳过
（MyBatis 对单参允许任意名，PO 属性也在此列，误差由人工看）。
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MOD = os.path.join(ROOT, "backend", "better-school")
DAO_DIR = os.path.join(MOD, "bs-infrastructure", "src", "main", "java", "com", "betterschool",
                       "infrastructure", "dao")
XML_DIR = os.path.join(MOD, "bs-app", "src", "main", "resources", "mybatis", "mapper")

METHOD_RE = re.compile(r"[\w<>,\[\]\.\?]+\s+(\w+)\s*\(([^;{)]*)\)\s*;")


def split_params(params_src):
    """按顶层逗号切分形参，忽略泛型里的逗号。"""
    out, depth, cur = [], 0, ""
    for ch in params_src:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


PARAM_RE = re.compile(r'@Param\(\s*"([^"]+)"\s*\)')


def parse_dao(path):
    """返回 {方法名: set(@Param 名)}；无 @Param 的方法值为 None（单参可任意名，跳过检查）。

    按分号切段：一段 = 一条方法声明（含其上的注解，可能跨行）。段内取最后一个
    `名字(` 作方法名，段内所有 @Param("x") 为该方法的可用参数名。
    """
    with open(path, encoding="utf-8") as fh:
        src = re.sub(r"//.*", "", fh.read())
        src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    # 先摘掉注解本身（@Param("x") 也有括号，会污染「最后一个 name( 是方法名」的判定）；
    # 注解里的参数名要在摘除前收集，按出现顺序绑到随后的第一个方法段
    pending = []
    methods = {}
    for seg in src.split(";"):
        names = set(PARAM_RE.findall(seg))
        stripped = re.sub(r"@[\w.]+(\([^()]*\))?", " ", seg)
        name_m = list(re.finditer(r"(\w+)\s*\(", stripped))
        if not name_m:
            pending.extend(names)
            continue
        name = name_m[-1].group(1)
        if name in ("if", "for", "while", "switch", "catch", "return", "new", "class", "interface"):
            pending.extend(names)
            continue
        names |= set(pending)
        pending = []
        methods[name] = names if names else None
    return methods


NS_RE = re.compile(r'<mapper[ \t]+namespace="([^"]+)"')
STMT_RE = re.compile(r'<(select|insert|update|delete)\s+id="([^"]+)"(.*?)</\1>', re.S)
PLACEHOLDER_RE = re.compile(r"[#$]\{\s*([A-Za-z_]\w*)")


def main():
    daos = {}
    for dirpath, _, filenames in os.walk(DAO_DIR):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, encoding="utf-8") as fh:
                head = fh.read()
            ns = re.search(r"package\s+([\w.]+)\s*;", head)
            fqcn = (ns.group(1) + "." + fn[:-5]) if ns else fn[:-5]
            daos[fqcn] = (fn, parse_dao(path))

    problems, skipped = [], 0
    for dirpath, _, filenames in os.walk(XML_DIR):
        for fn in filenames:
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, encoding="utf-8") as fh:
                xml = fh.read()
            ns_m = NS_RE.search(xml)
            if not ns_m:
                continue
            ns = ns_m.group(1)
            if ns not in daos:
                problems.append((fn, "-", f"namespace {ns} 无对应 DAO 文件"))
                continue
            _, methods = daos[ns]
            for m in STMT_RE.finditer(xml):
                kind, stmt_id, body = m.group(1), m.group(2), m.group(3)
                if stmt_id not in methods:
                    problems.append((fn, stmt_id, "XML 里有该语句但 DAO 无同名方法"))
                    continue
                declared = methods[stmt_id]
                if declared is None:
                    skipped += 1
                    continue
                # 去掉 <foreach item="x"> 声明的局部名
                local = set(re.findall(r'item="(\w+)"', body))
                local |= set(re.findall(r'collection="(\w+)"', body))
                for ph in PLACEHOLDER_RE.finditer(body):
                    name = ph.group(1)
                    if name in declared or name in local:
                        continue
                    problems.append((fn, stmt_id, f"占位符 #{{{name}}} 不在 DAO @Param 声明 {sorted(declared)}"))

    # DAO 有方法但 XML 缺语句（编译能过、运行时才炸）
    xml_ids = defaultdict(set)
    for dirpath, _, filenames in os.walk(XML_DIR):
        for fn in filenames:
            if not fn.endswith(".xml"):
                continue
            with open(os.path.join(dirpath, fn), encoding="utf-8") as fh:
                xml = fh.read()
            ns_m = NS_RE.search(xml)
            if not ns_m:
                continue
            for m in STMT_RE.finditer(xml):
                xml_ids[ns_m.group(1)].add(m.group(2))
            for sid in re.findall(r'<(?:select|insert|update|delete)\s+id="([^"]+)"', xml):
                xml_ids[ns_m.group(1)].add(sid)

    for fqcn, (fn, methods) in sorted(daos.items()):
        for name in methods:
            if name not in xml_ids.get(fqcn, set()):
                problems.append((fn, name, "DAO 有方法但 XML 无同名语句"))

    if problems:
        print("发现问题：")
        for fn, sid, msg in problems:
            print(f"  {fn} :: {sid}\n      {msg}")
    else:
        print("未发现问题。")
    print(f"（跳过单参无 @Param 的语句 {skipped} 条）")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
