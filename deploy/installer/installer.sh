#!/bin/sh
set -eu
# Host-side Docker inspection only; no socket is exposed to the installer image.
if [ "$(uname -s)" != Linux ] || [ "$(id -u)" != 0 ]; then
  echo '请在 Linux Docker 宿主机上以 root 执行。' >&2; exit 1
fi
if [ "$#" -lt 2 ]; then
  echo '用法: sh installer.sh /绝对路径/callnexus-deploy prepare|init|render|check|sql-check|sql-prepare|minio-init [参数]' >&2; exit 1
fi
tools_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
CALLNEXUS_DEPLOY_DIR=$(CDPATH= cd -- "$1" && pwd -P)
export CALLNEXUS_DEPLOY_DIR
shift
case "$CALLNEXUS_DEPLOY_DIR" in /) echo '拒绝挂载宿主机根目录。' >&2; exit 1;; esac
test -f "$CALLNEXUS_DEPLOY_DIR/compose.yaml" || { echo '部署目录缺少 compose.yaml' >&2; exit 1; }
if [ -n "${DOCKER_CONTEXT:-}" ]; then
  endpoint=$(docker context inspect "$DOCKER_CONTEXT" --format '{{.Endpoints.docker.Host}}')
else
  endpoint=${DOCKER_HOST:-$(docker context inspect --format '{{.Endpoints.docker.Host}}')}
fi
case "$endpoint" in unix://*) ;; *) echo '只支持本机 Linux Docker，不支持远程 Docker Context。' >&2; exit 1;; esac
docker info >/dev/null
# Serialize installer operations; never remove another installer's lock.
lock="$CALLNEXUS_DEPLOY_DIR/.installer-lock"
mkdir "$lock" 2>/dev/null || { echo '已有初始化操作或遗留 .installer-lock，请先确认。' >&2; exit 1; }
trap 'rmdir "$lock" 2>/dev/null || true' EXIT HUP INT TERM
CNX_INSTALLER_CONTAINER_PROJECTS=$(docker ps -a --format '{{.Label "com.docker.compose.project"}}')
export CNX_INSTALLER_CONTAINER_PROJECTS
volumes=$(docker volume ls --format '{{.Name}}')
volume_snapshot=''
for volume in $volumes; do
  labels=$(docker volume inspect --format '{{json .Labels}}' "$volume")
  volume_snapshot="$volume_snapshot
{\"name\":\"$volume\",\"labels\":$labels}"
done
export CNX_INSTALLER_VOLUMES="$volume_snapshot"
export CNX_INSTALLER_SNAPSHOT_READY=1
if [ "$1" = sql-prepare ]; then
  # Discover identity from the actual loaded image, never guess 999/1001.
  CNX_INSTALLER_MYSQL_UID=$(docker run --rm --pull=never --network=none --entrypoint id callnexus-mysql-service:v8.0 -u mysql)
  CNX_INSTALLER_MYSQL_GID=$(docker run --rm --pull=never --network=none --entrypoint id callnexus-mysql-service:v8.0 -g mysql)
  export CNX_INSTALLER_MYSQL_UID CNX_INSTALLER_MYSQL_GID
fi
if [ "$1" = prepare ]; then
  docker compose -p callnexus-install-tools --env-file /dev/null -f "$tools_dir/compose.installer.yaml" --profile tools run --rm --no-deps installer "$@"
else
  docker compose -p callnexus-install-tools --env-file /dev/null -f "$tools_dir/compose.installer.yaml" --profile tools run --rm --no-deps -T installer "$@"
fi
