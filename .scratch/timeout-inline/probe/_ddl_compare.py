"""票 09 验收：sql.md 与两份 schema.sql 逐表逐列对照。

归一化：去掉所有空白差异、统一大写、去掉 CREATE 里的 IF NOT EXISTS。
比较项：列名/类型/约束/索引/表选项（COMMENT 文本也纳入；逻辑表 timeout_task 与物理 00 的表注释不同，
单独比较时忽略表级 COMMENT）。
结果打印差异；退出码 1 = 有差异。
"""
import io
import re
import sys

ROOT = r"C:\Users\a1368\Desktop\better_school"


def read(path):
    return io.open(path, encoding="utf-8").read()


def strip_sql_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"--[^\n]*", "", text)
    return text


def split_statements(text):
    statements = []
    current = []
    in_quote = False
    quote_char = ""
    for ch in text:
        if in_quote:
            current.append(ch)
            if ch == quote_char:
                in_quote = False
            continue
        if ch in ("'", '"'):
            in_quote = True
            quote_char = ch
            current.append(ch)
            continue
        if ch == ";":
            statement = "".join(current).strip()
            if statement:
                statements.append(statement)
            current = []
            continue
        current.append(ch)
    tail = "".join(current).strip()
    if tail:
        statements.append(tail)
    return statements


def parse_tables(text):
    """返回 {表名: 规范化后的列/索引/表选项列表}"""
    tables = {}
    for statement in split_statements(strip_sql_comments(text)):
        normalized = re.sub(r"\s+", " ", statement).strip()
        match = re.search(
            r"CREATE TABLE (?:IF NOT EXISTS )?`?([A-Za-z0-9_]+)`? \((.*)\)(.*)$",
            normalized,
            flags=re.I | re.S,
        )
        if not match:
            continue
        name = match.group(1)
        body = match.group(2)
        tail = match.group(3)
        items = split_body_items(body)
        tables[name] = {
            "items": items,
            "tail": normalize_tail(tail),
        }
    return tables


def split_body_items(body):
    items = []
    current = []
    depth = 0
    in_quote = False
    quote_char = ""
    for ch in body:
        if in_quote:
            current.append(ch)
            if ch == quote_char:
                in_quote = False
            continue
        if ch in ("'", '"'):
            in_quote = True
            quote_char = ch
            current.append(ch)
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            items.append(normalize_item("".join(current)))
            current = []
            continue
        current.append(ch)
    if current:
        items.append(normalize_item("".join(current)))
    return [item for item in items if item]


def normalize_item(item):
    item = re.sub(r"\s+", " ", item).strip().upper()
    # 列名前后的反引号不影响语义
    item = item.replace("`", "")
    return item


def normalize_tail(tail):
    tail = re.sub(r"\s+", " ", tail).strip().upper()
    # ENGINE/CHARSET 的写法差异统一成关键字序，其余原样
    tail = re.sub(r"DEFAULT CHARSET = ", "DEFAULT CHARSET=", tail)
    return tail


def diff_tables(label, left, right, ignore_tail_names=()):
    problems = []
    left_names = set(left)
    right_names = set(right)
    for missing in sorted(right_names - left_names):
        problems.append(f"{label}: 左侧缺表 {missing}")
    for extra in sorted(left_names - right_names):
        problems.append(f"{label}: 左侧多表 {extra}")
    for name in sorted(left_names & right_names):
        a, b = left[name], right[name]
        if a["items"] != b["items"]:
            for index, (x, y) in enumerate(zip(a["items"], b["items"])):
                if x != y:
                    problems.append(f"{label}: 表 {name} 第 {index + 1} 项不同\n  sql.md : {x}\n  schema : {y}")
            if len(a["items"]) != len(b["items"]):
                problems.append(f"{label}: 表 {name} 项数不同 {len(a['items'])} vs {len(b['items'])}")
        if name not in ignore_tail_names and a["tail"] != b["tail"]:
            problems.append(f"{label}: 表 {name} 表选项不同\n  sql.md : {a['tail']}\n  schema : {b['tail']}")
    return problems


def main():
    sql_md = read(f"{ROOT}\\sql.md")
    section1_start = sql_md.index("## 1. 单体")
    section2_start = sql_md.index("## 2. 营销")
    section1 = sql_md[section1_start:section2_start]
    section2 = sql_md[section2_start:]

    md1 = parse_tables(section1)
    md2 = parse_tables(section2)
    bs = parse_tables(read(f"{ROOT}\\backend\\better-school\\sql\\schema.sql"))
    mkt = parse_tables(read(f"{ROOT}\\backend\\marketing\\sql\\schema.sql"))

    problems = []

    # 1. 29 张业务表：schema 去掉 16 张 timeout 分表后与 §1 的非 timeout 表全等
    bs_business = {k: v for k, v in bs.items() if not k.startswith("timeout_task_")}
    md1_business = {k: v for k, v in md1.items() if not k.startswith("timeout_task")}
    problems += diff_tables("§1 业务表 vs better-school/schema.sql", md1_business, bs_business)

    # 2. 超时逻辑表（sql.md 只写 00 一份）与 schema 的 00 全等；表级 COMMENT 允许不同
    problems += diff_tables("§1 timeout_task_00 vs schema", {"timeout_task_00": md1["timeout_task_00"]},
                            {"timeout_task_00": bs["timeout_task_00"]}, ignore_tail_names=("timeout_task_00",))

    # 3. schema 里 16 张分表逐张互相全等（表名不同，比较列/索引/表选项）
    base = bs["timeout_task_00"]
    for slot in range(16):
        name = f"timeout_task_{slot:02d}"
        if name not in bs:
            problems.append(f"schema: 缺分表 {name}")
            continue
        table = bs[name]
        if table["items"] != base["items"]:
            problems.append(f"schema: 分表 {name} 与 timeout_task_00 的列/索引不同")
        if table["tail"] != base["tail"] and "COMMENT = '超时任务分表" not in table["tail"]:
            problems.append(f"schema: 分表 {name} 表选项与 00 不同：{table['tail']}")
    for slot in range(16):
        name = f"timeout_task_{slot:02d}"
        if name not in bs:
            problems.append(f"schema: 缺分表 {name}")

    # 4. 营销 22 张
    problems += diff_tables("§2 vs marketing/schema.sql", md2, mkt)

    print(f"sql.md §1 表数: {len(md1)}（业务 {len(md1_business)} + timeout 1 份）")
    print(f"better-school/schema.sql 表数: {len(bs)}（业务 {len(bs_business)} + 超时 {len(bs) - len(bs_business)}）")
    print(f"sql.md §2 表数: {len(md2)}；marketing/schema.sql 表数: {len(mkt)}")
    if problems:
        print("\n差异：")
        for problem in problems:
            print(" - " + problem)
        return 1
    print("\nOK：所有表逐列/索引/约束全等")
    return 0


if __name__ == "__main__":
    sys.exit(main())
