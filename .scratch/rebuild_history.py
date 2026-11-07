# -*- coding: utf-8 -*-
"""方案 B：切除受损提交，按「保留提交的绝对树」重放出一条完整可打包的历史。
   - 只读取每个保留提交的 tree（已确认完整），不读任何损坏对象，不需要计算 diff。
   - commit message / author / committer / 时间 / 附加头全部按原始字节保留。
   - 跳过不可读提交，其子提交自动重接到它最近的可读祖先。
   - 原 refs 一律不动，新链写到 refs/rebuilt/*。
"""
import subprocess, sys, json

def git(*args, stdin=None, devnull=False):
    return subprocess.run(['git'] + list(args),
                          input=stdin,
                          stdout=subprocess.DEVNULL if devnull else subprocess.PIPE,
                          stderr=subprocess.PIPE)

revs = git('rev-list', '--all', '--topo-order', '--reverse').stdout.split()
revs = [r.decode() for r in revs]
print('total commits:', len(revs), flush=True)

readable = {}
for c in revs:
    readable[c] = git('ls-tree', '-r', c, devnull=True).returncode == 0
n_ok = sum(readable.values())
print('readable: %d   damaged(skipped): %d' % (n_ok, len(revs) - n_ok), flush=True)

mapping = {}
skipped = []
for i, c in enumerate(revs):
    raw = git('cat-file', 'commit', c).stdout
    header, sep, msg = raw.partition(b'\n\n')
    if not sep:
        raise SystemExit('unexpected commit format: ' + c)
    lines = header.split(b'\n')
    tree_line = lines[0]
    rest = lines[1:]
    parent_lines = [l for l in rest if l.startswith(b'parent ')]
    tail = [l for l in rest if not l.startswith(b'parent ')]

    newp = []
    for pl in parent_lines:
        p = pl.split()[1].decode()
        m = mapping.get(p)
        if m and m not in newp:
            newp.append(m)

    if not readable[c]:
        newp_before = len(parent_lines)
        mapping[c] = newp[0] if newp else None
        skipped.append((c, newp_before))
        continue

    hdr = [tree_line] + [b'parent ' + p.encode() for p in newp] + tail
    newraw = b'\n'.join(hdr) + b'\n\n' + msg
    newsha = git('hash-object', '-t', 'commit', '-w', '--stdin', stdin=newraw).stdout.strip().decode()
    if not newsha:
        raise SystemExit('hash-object failed at ' + c)
    mapping[c] = newsha
    if (i + 1) % 50 == 0:
        print('  rebuilt %d/%d' % (i + 1, len(revs)), flush=True)

# 新 refs
refs = git('for-each-ref', '--format=%(refname) %(objectname)', 'refs/heads').stdout.decode().strip().split('\n')
created = []
for line in refs:
    name, sha = line.split()
    m = mapping.get(sha)
    if not m:
        print('WARN: ref %s head unmapped, skipped' % name); continue
    newref = 'refs/rebuilt/' + name[len('refs/heads/'):]
    git('update-ref', newref, m)
    created.append((name, sha, m))
    print('%-34s %s -> %s' % (name, sha[:8], m[:8]))

print('\nnew commits: %d' % len(set(mapping[c] for c in revs if readable[c])))
print('skipped %d commits (first-parent reattached)' % len(skipped))
json.dump({'mapping': mapping, 'skipped': [s[0] for s in skipped]},
          open(r'C:/Users/a1368/AppData/Local/Temp/rebuild-map.json', 'w'))
print('map saved')
