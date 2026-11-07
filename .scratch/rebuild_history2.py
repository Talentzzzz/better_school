# -*- coding: utf-8 -*-
"""方案 B（严格判据版）：切除受损提交，重放出一条所有对象齐全、可打包的历史。

判据升级：一个提交算「完好」必须同时满足
  1) git ls-tree -r 成功（所有 tree 存在）
  2) 其树里列出的任何 blob/tree sha 都不在缺失集合内（内容完整）
—— 只看 ls-tree 返回码会漏掉「树结构完整但引用了已丢失 blob」的提交。
"""
import subprocess, json

MISSING = set(l.strip() for l in open(r'C:/Users/a1368/AppData/Local/Temp/m1.txt') if l.strip())

def git(*args, stdin=None, devnull=False):
    return subprocess.run(['git'] + list(args), input=stdin,
                          stdout=subprocess.DEVNULL if devnull else subprocess.PIPE,
                          stderr=subprocess.PIPE)

revs = [r.decode() for r in git('rev-list', '--all', '--topo-order', '--reverse').stdout.split()]
print('total commits:', len(revs), flush=True)

good = {}
for i, c in enumerate(revs):
    res = git('ls-tree', '-r', '-t', c)
    if res.returncode != 0:
        good[c] = ('missing-tree', None)
        continue
    hit = None
    for line in res.stdout.split(b'\n'):
        if not line:
            continue
        sha = line.split(b'\t')[0].split()[-1].decode()
        if sha in MISSING:
            hit = sha
            break
    good[c] = ('ok', None) if hit is None else ('missing-obj', hit)

n_ok = sum(1 for v in good.values() if v[0] == 'ok')
why = {}
for v in good.values():
    why[v[0]] = why.get(v[0], 0) + 1
print('readable(fully): %d   damaged: %d  %s' % (n_ok, len(revs) - n_ok, why), flush=True)

mapping = {}
for c in revs:
    raw = git('cat-file', 'commit', c).stdout
    header, sep, msg = raw.partition(b'\n\n')
    lines = header.split(b'\n')
    tree_line = lines[0]
    rest = lines[1:]
    parent_lines = [l for l in rest if l.startswith(b'parent ')]
    tail = [l for l in rest if not l.startswith(b'parent ')]

    newp = []
    for pl in parent_lines:
        m = mapping.get(pl.split()[1].decode())
        if m and m not in newp:
            newp.append(m)

    if good[c][0] != 'ok':
        mapping[c] = newp[0] if newp else None
        continue

    newraw = b'\n'.join([tree_line] + [b'parent ' + p.encode() for p in newp] + tail) + b'\n\n' + msg
    newsha = git('hash-object', '-t', 'commit', '-w', '--stdin', stdin=newraw).stdout.strip().decode()
    if not newsha:
        raise SystemExit('hash-object failed at ' + c)
    mapping[c] = newsha

refs = [l.split() for l in git('for-each-ref', '--format=%(refname) %(objectname)', 'refs/heads').stdout.decode().strip().split('\n')]
for name, sha in refs:
    m = mapping[sha]
    git('update-ref', 'refs/rebuilt/' + name[len('refs/heads/'):], m)
    print('%-34s %s -> %s %s' % (name, sha[:8], m[:8], '' if m != sha else '(unchanged)'))

json.dump({'mapping': mapping, 'good': {k: v[0] for k, v in good.items()}},
          open(r'C:/Users/a1368/AppData/Local/Temp/rebuild-map2.json', 'w'))
print('\ndone. new chain commits: %d' % sum(1 for v in good.values() if v[0] == 'ok'))
