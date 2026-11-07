# -*- coding: utf-8 -*-
"""从损坏 pack 中尽力提取全部对象为 loose（v2：正确的条目边界 + CRC 预检）。可重复执行。"""
import struct, zlib, hashlib, os, bisect

PACK = r'C:/Users/a1368/Desktop/bs-pack-quarantine/pack-d11316d30ffa4984f81a1dda8b33d2112bd1de55.pack'
IDX  = r'C:/Users/a1368/Desktop/bs-pack-quarantine/pack-d11316d30ffa4984f81a1dda8b33d2112bd1de55.idx'
OBJDIR = r'C:/Users/a1368/Desktop/better_school/.git/objects'

idx = open(IDX, 'rb').read()
assert idx[:4] == b'\xfftOc'
fanout = struct.unpack('>256I', idx[8:8+1024])
n = fanout[255]
shas = [idx[8+1024+i*20: 8+1024+i*20+20] for i in range(n)]
crcs = struct.unpack('>%dI' % n, idx[8+1024+n*20: 8+1024+n*24])
offsets = struct.unpack('>%dI' % n, idx[8+1024+n*24: 8+1024+n*28])

data = open(PACK, 'rb').read()
print('entries:', n, 'pack size:', len(data), flush=True)

# 正确的条目字节范围：按偏移排序，每个条目到下一个偏移（pack 尾部 20 字节 sha 前）
order = sorted(range(n), key=lambda i: offsets[i])
end_of = {}
for k in range(n):
    i = order[k]
    end = offsets[order[k+1]] if k+1 < n else len(data) - 20
    end_of[i] = end

TYPE_NAMES = {1: 'commit', 2: 'tree', 3: 'blob', 4: 'tag'}

def read_obj(off, depth=0):
    if depth > 64:
        raise ValueError('delta chain too deep')
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
        btype, bcontent = read_obj(off - rel, depth + 1)
        delta = zlib.decompress(data[pos:])
        return btype, apply_delta(bcontent, delta)
    if typ == 7:
        base_sha = data[pos:pos + 20]; pos += 20
        i = bisect.bisect_left(shas, base_sha)
        if i >= n or shas[i] != base_sha:
            raise ValueError('ref-delta base missing')
        btype, bcontent = read_obj(offsets[i], depth + 1)
        delta = zlib.decompress(data[pos:])
        return btype, apply_delta(bcontent, delta)
    if typ not in TYPE_NAMES:
        raise ValueError('unsupported type %d' % typ)
    return TYPE_NAMES[typ], zlib.decompress(data[pos:])

def apply_delta(base, delta):
    pos = 0
    def varint():
        nonlocal pos
        r = 0; shift = 0
        while True:
            b = delta[pos]; pos += 1
            r |= (b & 0x7f) << shift
            shift += 7
            if not (b & 0x80):
                break
        return r
    src_size = varint()
    if src_size != len(base):
        raise ValueError('src size mismatch')
    tgt_size = varint()
    out = bytearray()
    while pos < len(delta):
        op = delta[pos]; pos += 1
        if op & 0x80:
            cp_off = 0; cp_size = 0
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
    if len(out) != tgt_size:
        raise ValueError('tgt size mismatch')
    return bytes(out)

ok = bad = skip = crcbad = 0
bad_list = []
for i in range(n):
    sha = shas[i]; off = offsets[i]
    if off & 0x80000000:
        bad += 1; bad_list.append((sha.hex(), 'large-offset')); continue
    if zlib.crc32(data[off:end_of[i]]) & 0xffffffff != crcs[i]:
        crcbad += 1; bad_list.append((sha.hex(), 'crc @%d' % off)); continue
    try:
        tname, content = read_obj(off)
        h = hashlib.sha1(('%s %d' % (tname, len(content))).encode() + b'\x00' + content).hexdigest()
        if h != sha.hex():
            bad += 1; bad_list.append((sha.hex(), 'sha mismatch')); continue
        d = os.path.join(OBJDIR, sha.hex()[:2])
        fp = os.path.join(d, sha.hex()[2:])
        if not os.path.exists(fp):
            os.makedirs(d, exist_ok=True)
            tmp = fp + '.tmp'
            with open(tmp, 'wb') as f:
                f.write(zlib.compress(('%s %d' % (tname, len(content))).encode() + b'\x00' + content))
            os.replace(tmp, fp)
            ok += 1
        else:
            skip += 1
    except Exception as e:
        bad += 1; bad_list.append((sha.hex(), str(e)[:50]))
    if (i + 1) % 10000 == 0:
        print('progress %d/%d' % (i + 1, n), flush=True)

print('extracted:', ok, 'already-present:', skip, 'bad:', bad, 'crc-bad:', crcbad, flush=True)
for b in bad_list[:20]:
    print('  BAD:', b[0], b[1])
