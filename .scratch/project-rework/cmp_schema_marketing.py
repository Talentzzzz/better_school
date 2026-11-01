"""Compare sql.md section 2 (marketing, bs_marketing) table DDL against backend/marketing/sql/schema.sql.

口径（票 08）：只比 表名 / 列名 / 类型 / NOT NULL / 默认值 / 键 / 顺序 / COMMENT。
`IF NOT EXISTS`、反引号、表级尾选项（ENGINE/CHARSET/表 COMMENT）不在比对轴内，归一化时剥掉；
列级 COMMENT 在括号体内，参与比对。
"""
import re
import difflib

MD_PATH = r'C:\Users\a1368\Desktop\better_school\sql.md'
SQL_PATH = r'C:\Users\a1368\Desktop\better_school\backend\marketing\sql\schema.sql'


def strip_line_comments(text):
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('--'):
            continue
        out.append(line)
    return "\n".join(out)


def parse_tables(text):
    """Return {table_name: normalized_body} preserving order (body = inside outer parens)."""
    text = strip_line_comments(text)
    tables = {}
    for m in re.finditer(r'CREATE TABLE(?: IF NOT EXISTS)? [`]?(\w+)[`]?\s*\(', text):
        name = m.group(1)
        i = text.index('(', m.end() - 1)
        depth = 0
        j = i
        while j < len(text):
            c = text[j]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        body = text[i + 1:j]
        body = body.replace('`', '')
        # 类型关键字大小写不敏感（MySQL 语义等价；sql.md §2 strategy_weight 用小写、schema.sql 用大写）
        body = body.lower()
        body = re.sub(r'\s+', ' ', body).strip()
        tables[name] = body
    return tables


md = open(MD_PATH, encoding='utf-8').read()
sec2 = md.split('## 2. ')[1].split('## 3. ')[0]
md_tables = parse_tables(sec2)

sql = open(SQL_PATH, encoding='utf-8').read()
sql_tables = parse_tables(sql)

print(f"sql.md sec2 tables: {len(md_tables)}")
print(f"schema.sql tables : {len(sql_tables)}")

only_md = [t for t in md_tables if t not in sql_tables]
only_sql = [t for t in sql_tables if t not in md_tables]
print(f"only in sql.md  : {only_md}")
print(f"only in schema  : {only_sql}")

diff_count = 0
for t in md_tables:
    if t not in sql_tables:
        continue
    a, b = md_tables[t], sql_tables[t]
    if a != b:
        diff_count += 1
        print(f"\n=== DIFF {t} ===")
        ta, tb = a.split(' '), b.split(' ')
        sm = difflib.SequenceMatcher(None, ta, tb)
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == 'equal':
                continue
            print(f"  {tag}: md[{' '.join(ta[i1:i2])}]  ->  sql[{' '.join(tb[j1:j2])}]")

identical = len(md_tables) - diff_count
print(f"\n{diff_count} table(s) differ, {identical} identical")
if diff_count == 0 and not only_md and not only_sql and len(md_tables) == len(sql_tables):
    print("逐表逐列全等 ✔")
