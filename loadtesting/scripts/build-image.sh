#!/usr/bin/env bash
# 在「有 Docker + JDK21 + Maven」的开发机上执行：编译 -> 打镜像 -> 导出 tar 包。
#
#   ./scripts/build-image.sh
#
# 产物：loadtesting/lt-inventory-app-1.0.0.tar（未压缩，可直接 docker load -i）
# 之后传到应用机导入即可，应用机不需要 JDK / Maven / 源码：
#   scp lt-inventory-app-1.0.0.tar root@101.201.49.50:/opt/loadtesting/
#   ssh root@101.201.49.50 'cd /opt/loadtesting && docker load -i lt-inventory-app-1.0.0.tar'
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
IMAGE="${APP_IMAGE:-lt-inventory-app:1.0.0}"
NAME="${IMAGE%%:*}"
TAG="${IMAGE##*:}"
TARBALL="${HERE}/${NAME}-${TAG}.tar"

echo ">>> 1/4 编译 jar"
cd "${HERE}/app"
mvn -B -q -DskipTests package

echo ">>> 2/4 构建镜像 ${IMAGE}"
docker build -f Dockerfile.runtime -t "${IMAGE}" .

echo ">>> 3/4 导出 ${TARBALL}"
docker save -o "${TARBALL}" "${IMAGE}"

# Docker 24+ / Docker Desktop 导出的可能是 OCI 布局（blobs/sha256/...，层是压缩的），
# 老版本 Docker 的 load 不认；只有带 layer.tar 的才是经典 docker archive。
if ! tar tf "${TARBALL}" 2>/dev/null | grep -q "/layer.tar$"; then
  echo ">>> 4/4 检测到 OCI 布局，转换为经典 docker 归档"
  PY_BIN=$(command -v python3 || command -v python || true)
  if [ -z "${PY_BIN}" ]; then
    echo "!! 需要 python3 才能转换格式；也可以直接用项目里已提供的 tar 包"
    exit 1
  fi
  "${PY_BIN}" "${HERE}/scripts/oci2docker.py" "${TARBALL}" "${TARBALL}.new" "${IMAGE}"
  mv -f "${TARBALL}.new" "${TARBALL}"
else
  echo ">>> 4/4 已是经典 docker 归档"
fi
ls -lh "${TARBALL}"

echo
echo "接下来："
echo "  scp ${TARBALL} root@101.201.49.50:/opt/loadtesting/"
echo "  ssh root@101.201.49.50 'cd /opt/loadtesting && docker load -i $(basename "${TARBALL}")'"
echo "  ssh root@101.201.49.50 'cd /opt/loadtesting && docker compose -f docker-compose-app.yml up -d'"
