#!/usr/bin/env python3
"""把 OCI 布局的镜像归档（blobs/sha256/...）转成经典 docker archive（manifest.json + layer.tar）。

Docker Desktop / 新版 Docker 的 docker save 可能导出 OCI 布局（层是压缩的 blob），
老版本 Docker（< 25）的 docker load 不认，用这个脚本转一下就行。

  python3 scripts/oci2docker.py in.tar out.tar lt-inventory-app:1.0.0
"""
import gzip
import json
import os
import sys
import tarfile
import tempfile

if len(sys.argv) != 4:
    print(__doc__)
    sys.exit(1)

src_tar, out_tar, repo_tag = sys.argv[1], sys.argv[2], sys.argv[3]


class _Prepend:
    """把已读出的开头几个字节拼回流的头部。"""

    def __init__(self, head, fp):
        self._head = head
        self._fp = fp

    def read(self, n=-1):
        if self._head:
            if n is None or n < 0:
                data, self._head = self._head, b""
                return data + self._fp.read()
            data, self._head = self._head[:n], self._head[n:]
            return data
        return self._fp.read(n)


def load_json(tf, name):
    f = tf.extractfile(name)
    return json.loads(f.read().decode("utf-8"))


with tarfile.open(src_tar, "r:") as tf:
    names = set(tf.getnames())

    # 1) 找到 image manifest：index.json -> manifest（可能是 manifest list）-> image manifest
    manifest = None
    if "index.json" in names:
        index = load_json(tf, "index.json")
        for m in index["manifests"]:
            ann = m.get("annotations") or {}
            if ann.get("vnd.docker.reference.type") == "attestation-manifest":
                continue
            manifest = load_json(tf, "blobs/sha256/" + m["digest"].split(":", 1)[1])
            break
    if manifest is None and "manifest.json" in names:
        manifest = load_json(tf, "manifest.json")[0]

    if "manifests" in manifest and "config" not in manifest:
        for m in manifest["manifests"]:
            ann = m.get("annotations") or {}
            if ann.get("vnd.docker.reference.type") == "attestation-manifest":
                continue
            manifest = load_json(tf, "blobs/sha256/" + m["digest"].split(":", 1)[1])
            break

    config_digest = manifest["config"]["digest"].split(":", 1)[1]
    layer_digests = [layer["digest"].split(":", 1)[1] for layer in manifest["layers"]]

    # 2) 边读边写，不落大量中间文件
    out = tarfile.open(out_tar, "w", format=tarfile.GNU_FORMAT)
    try:
        # config
        cfg = tf.extractfile("blobs/sha256/" + config_digest)
        data = cfg.read()
        cfg_name = config_digest + ".json"
        info = tarfile.TarInfo(cfg_name)
        info.size = len(data)
        out.addfile(info, __import__("io").BytesIO(data))

        layers = []
        for hexd in layer_digests:
            member_name = "blobs/sha256/" + hexd
            src = tf.extractfile(member_name)
            # 逐层解到临时文件（一次只留一个），再写进新归档
            with tempfile.NamedTemporaryFile(delete=False) as tmp:
                tmp_path = tmp.name
                head = src.read(2)
                if head == b"\x1f\x8b":
                    gz = gzip.GzipFile(fileobj=_Prepend(head, src))
                    while True:
                        chunk = gz.read(1 << 20)
                        if not chunk:
                            break
                        tmp.write(chunk)
                else:
                    tmp.write(head)
                    while True:
                        chunk = src.read(1 << 20)
                        if not chunk:
                            break
                        tmp.write(chunk)
            size = os.path.getsize(tmp_path)
            info = tarfile.TarInfo(hexd + "/layer.tar")
            info.size = size
            with open(tmp_path, "rb") as fh:
                out.addfile(info, fh)

            ver = b"1.0"
            info = tarfile.TarInfo(hexd + "/VERSION")
            info.size = len(ver)
            out.addfile(info, __import__("io").BytesIO(ver))
            os.remove(tmp_path)
            layers.append(hexd + "/layer.tar")

        manifest_obj = [{"Config": cfg_name, "RepoTags": [repo_tag], "Layers": layers}]
        data = json.dumps(manifest_obj).encode("utf-8")
        info = tarfile.TarInfo("manifest.json")
        info.size = len(data)
        out.addfile(info, __import__("io").BytesIO(data))

        name, tag = repo_tag.split(":", 1)
        data = json.dumps({name: {tag: layers[-1].split("/")[0]}}).encode("utf-8")
        info = tarfile.TarInfo("repositories")
        info.size = len(data)
        out.addfile(info, __import__("io").BytesIO(data))
    finally:
        out.close()

print("converted -> %s (%d MB, %d layers)" % (out_tar, os.path.getsize(out_tar) // 1048576, len(layer_digests)))
