"""Compare sql.md section 1 (better_school) table DDL against backend/better-school/sql/schema.sql."""
import re
import sys


def strip_line_comments(text):
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('--'):
            continue
        out.append(line)
    return "\n".join(out)


def normalize(ddl):
    ddl = strip_line_comments(ddl)
    ddl = re.sub(r'/\*.*?\*/', '', ddl, flags=re.S)
    # collapse whitespace
    ddl = re.sub(r'\s+', ' ', ddl).strip()
    ddl = ddl.rstrip(';').strip()
    return ddl


def parse(text, source):
    """Return {table_name: normalized_ddl} preserving order."""
    tables = {}
    for m in re.finditer(r'CREATE TABLE(?: IF NOT EXISTS)? [`]?(\w+)[`]?\s*\(', text):
        name = m.group(1)
        start = m.start()
        # find matching close paren then semicolon
        depth = 0
        i = text.index('(', m.end() - 1)
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
        k = text.index(';', j)
        tables[name] = normalize(text[start:k + 1])
    return tables


md = open(r'C:\Users\a1368\Desktop\better_school\sql.md', encoding='utf-8').read()
# section 1 only: up to "## 2."
sec1 = md.split('## 2. ')[0]
md_tables = parse(sec1, 'sql.md')

sql = open(r'C:\Users\a1368\Desktop\better_school\backend\better-school\sql\schema.sql', encoding='utf-8').read()
sql_tables = parse(sql, 'schema.sql')

print(f"sql.md sec1 tables: {len(md_tables)}")
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
        # token level diff
        ta, tb = a.split(' '), b.split(' ')
        import difflib
        sm = difflib.SequenceMatcher(None, ta, tb)
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == 'equal':
                continue
            print(f"  {tag}: md[{' '.join(ta[i1:i2])}]  ->  sql[{' '.join(tb[j1:j2])}]")

print(f"\n{diff_count} table(s) differ, {len(md_tables) - diff_count} identical")
