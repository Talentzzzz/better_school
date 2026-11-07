# -*- coding: utf-8 -*-
"""对损坏 pack 中「delta 流完好、仅基对象缺失」的条目做二次救援：
   基对象优先从仓库现有 loose 库解析，其次在 pack 内递归解析。
   仅当重建结果 SHA 与目标完全一致时才写盘。"""
import struct, zlib, hashlib, os, sys

PACK = r'C:/Users/a1368/Desktop/bs-pack-quarantine/pack-d11316d30ffa4984f81a1dda8b33d2112bd1de55.pack'
IDX  = r'C:/Users/a1368/Desktop/bs-pack-quarantine/pack-d11316d30ffa4984f81a1dda8b33d2112bd1de55.idx'
OBJDIR = r'C:/Users/a1368/Desktop/better_school/.git/objects'

idx = open(IDX, 'rb').read()
n = struct.unpack('>256I', idx[8:8 + 1024])[255]
shas = [idx[8 + 1024 + i * 20: 8 + 1024 + i * 20 + 20] for i in range(n)]
offsets = struct.unpack('>%dI' % n, idx[8 + 1024 + n * 24: 8 + 1024 + n * 28])
data = open(PACK, 'rb').read()

off2sha = {offsets[i]: shas[i].hex() for i in range(n)}
sha2off = {shas[i].hex(): offsets[i] for i in range(n)}

TYPE_NAMES = {1: 'commit', 2: 'tree', 3: 'blob', 4: 'tag'}
_memo = {}


def read_loose(sha):
    """从仓库读 loose 对象，返回 (type, content)；无效返回 None"""
    fp = os.path.join(OBJDIR, sha[:2], sha[2:])
    if not os.path.exists(fp):
        return None
    try:
        raw = zlib.decompress(open(fp, 'rb').read())
        nul = raw.index(b'\x00')
        tname, size = raw[:nul].decode().split(' ')
        content = raw[nul + 1:]
        if len(content) != int(size):
            return None
        if hashlib.sha1(raw).hexdigest() != sha:
            return None
        return tname, content
    except Exception:
        return None


def apply_delta(base, delta):
    pos = 0

    def varint():
        nonlocal pos
        r = 0
        shift = 0
        while True:
            b = delta[pos]; pos += 1
            r |= (b & 0x7f) << shift
            shift += 7
            if not (b & 0x80):
                break
        return r

    if varint() != len(base):
        raise ValueError('src size mismatch')
    tgt = varint()
    out = bytearray()
    while pos < len(delta):
        op = delta[pos]; pos += 1
        if op & 0x80:
            cp_off = cp_size = 0
            for i in range(4):
                if op & (1 << i):
                    cp_off |= delta[pos] << (8 * i); pos += 1
            for i in range(3):
                if op & (1 << (4 + i)):
                    cp_size |= delta[pos] << (8 * i); pos += 1
            if cp_size == 0:
                cp_size = 0x10000
            out += base[cp_off:cp_off + cp_size]
        elif op:
            out += delta[pos:pos + op]; pos += op
        else:
            raise ValueError('bad delta op')
    if len(out) != tgt:
        raise ValueError('tgt size mismatch')
    return bytes(out)


def read_at(off, depth=0, via=''):
    """解析 pack 内某偏移的对象，返回 (type, content, 来源说明)"""
    if depth > 64:
        raise ValueError('chain too deep')
    if off in _memo:
        return _memo[off]
    sha = off2sha.get(off)
    # 优先用 loose 库里已有的完整副本
    if sha:
        lo = read_loose(sha)
        if lo:
            res = (lo[0], lo[1], 'loose')
            _memo[off] = res
            return res

    pos = off
    b = data[pos]; pos += 1
    typ = (b >> 4) & 7
    size = b & 15
    shift = 4
    while b & 0x80:
        b = data[pos]; pos += 1
        size |= (b & 0x7f) << shift
        shift += 7

    if typ == 6:
        b = data[pos]; pos += 1
        rel = b & 0x7f
        while b & 0x80:
            b = data[pos]; pos += 1
            rel = ((rel + 1) << 7) | (b & 0x7f)
        btype, bcontent, bsrc = read_at(off - rel, depth + 1)
        delta = zlib.decompressobj().decompress(data[pos:])
        res = (btype, apply_delta(bcontent, delta), 'delta<-' + bsrc)
        _memo[off] = res
        return res

    if typ == 7:
        base_sha = data[pos:pos + 20].hex(); pos += 20
        if base_sha not in sha2off:
            raise ValueError('ref-delta base not in idx')
        btype, bcontent, bsrc = read_at(sha2off[base_sha], depth + 1)
        delta = zlib.decompressobj().decompress(data[pos:])
        res = (btype, apply_delta(bcontent, delta), 'refdelta<-' + bsrc)
        _memo[off] = res
        return res

    if typ not in TYPE_NAMES:
        raise ValueError('bad type %d' % typ)
    raw = zlib.decompress(data[pos:])
    res = (TYPE_NAMES[typ], raw, 'pack')
    _memo[off] = res
    return res


targets = [l.strip() for l in open(r'C:/Users/a1368/AppData/Local/Temp/m1.txt') if l.strip()]
recovered = failed = 0
for t in targets:
    off = sha2off.get(t)
    if off is None:
        print('%-12s 不在 idx' % t[:12]); failed += 1; continue
    try:
        tname, content, src = read_at(off)
        full = ('%s %d' % (tname, len(content))).encode() + b'\x00' + content
        h = hashlib.sha1(full).hexdigest()
        if h != t:
            print('%-12s 重建 SHA 不匹配 (%s) via %s' % (t[:12], h[:12], src)); failed += 1; continue
        d = os.path.join(OBJDIR, t[:2]); os.makedirs(d, exist_ok=True)
        fp = os.path.join(d, t[2:])
        tmp = fp + '.tmp'
        open(tmp, 'wb').write(zlib.compress(full))
        os.replace(tmp, fp)
        print('%-12s RECOVERED %s %dB via %s' % (t[:12], tname, len(content), src))
        recovered += 1
    except Exception as e:
        print('%-12s FAILED: %s' % (t[:12], str(e)[:60])); failed += 1

print('\n=== recovered: %d  failed: %d ===' % (recovered, failed))
