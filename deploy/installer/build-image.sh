#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
image=${CALLNEXUS_INSTALLER_IMAGE:-callnexus-installer:2026.11.06}
if [ "${CALLNEXUS_INSTALLER_OFFLINE_BUILD:-0}" = 1 ]; then
  base=${CALLNEXUS_INSTALLER_BASE_IMAGE:-callnexus-installer-base:2026.11.06}
  docker image inspect "$base" >/dev/null 2>&1 || { echo '缺少已准备的离线基础镜像，不能只导入原版 Python slim 镜像。' >&2; exit 1; }
  docker build --pull=false -f Dockerfile.offline --build-arg "INSTALLER_BASE_IMAGE=$base" -t "$image" .
else
  docker build --build-arg "PYTHON_IMAGE=${CALLNEXUS_INSTALLER_PYTHON_IMAGE:-python:3.12-slim-bookworm}" -t "$image" .
fi
docker run --rm --network=none --read-only --cap-drop=ALL --security-opt no-new-privileges:true "$image" help
mkdir -p images
archive=images/callnexus-installer-20261106.tar
docker save -o "$archive" "$image"
sha256sum "$archive" > images/SHA256SUMS
echo '初始化镜像已构建、help 自检并导出。尚未运行任何业务服务。'
