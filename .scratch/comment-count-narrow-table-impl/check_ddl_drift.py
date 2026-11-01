import re, sys, io

def read(p):
    with io.open(p, encoding='utf-8') as f:
        return f.read()

schema = read('backend/better-school/sql/schema.sql')
sqlmd = read('sql.md')

def cols_from_ddl(text, table):
    m = re.search(r'CREATE TABLE IF NOT EXISTS %s\s*\((.*?)\n\)\s*ENGINE' % table, text, re.S)
    if not m:
        return None, None
    body = m.group(1)
    cols = []
    for line in body.split('\n'):
        line = line.strip()
        if not line or line.startswith('--'):
            continue
        if re.match(r'(PRIMARY KEY|UNIQUE KEY|KEY|CONSTRAINT|INDEX)\b', line, re.I):
            continue
        name = line.split()[0].strip(',')
        cols.append(name)
    return cols, m.group(0)

tables = ['post_comment', 'post_comment_count', 'post_comment_like_count',
          'post_comment_like', 'post_comment_feed_index']
ok = True
for t in tables:
    sc, _ = cols_from_ddl(schema, t)
    md, _ = cols_from_ddl(sqlmd, t)
    if sc is None or md is None:
        print('MISSING DDL  %-26s schema=%s sql.md=%s' % (t, sc is not None, md is not None)); ok = False; continue
    if sc != md:
        print('DRIFT        %-26s' % t)
        print('   schema only:', [c for c in sc if c not in md])
        print('   sql.md only:', [c for c in md if c not in sc])
        print('   order same :', sc == md)
        ok = False
    else:
        print('OK           %-26s %d cols' % (t, len(sc)))
print('ALL IDENTICAL' if ok else 'DRIFT FOUND')
sys.exit(0 if ok else 1)
