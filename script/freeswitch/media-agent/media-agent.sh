#!/usr/bin/env bash
set -uo pipefail

api_base_url="${CALLNEXUS_API_BASE_URL:?CALLNEXUS_API_BASE_URL is required}"
node_code="${CALLNEXUS_NODE_CODE:?CALLNEXUS_NODE_CODE is required}"
node_token="${CALLNEXUS_NODE_TOKEN:?CALLNEXUS_NODE_TOKEN is required}"
media_root="${CALLNEXUS_MEDIA_ROOT:-/var/lib/freeswitch/sounds/callnexus}"
poll_seconds="${CALLNEXUS_POLL_SECONDS:-10}"
agent_version="${CALLNEXUS_AGENT_VERSION:-1.0.0}"
work_dir="${CALLNEXUS_AGENT_WORK_DIR:-/tmp/callnexus-media-agent}"

mkdir -p "${work_dir}" "${media_root}"

api() {
  curl --fail --silent --show-error --connect-timeout 10 --max-time 300 \
    -H "X-CallNexus-Node-Code: ${node_code}" \
    -H "X-CallNexus-Node-Token: ${node_token}" "$@"
}

report() {
  local task_id="$1" lease_token="$2" success="$3" reason="${4:-}"
  reason="$(printf '%s' "${reason}" | tail -c 900 | jq -Rs .)"
  api -X POST "${api_base_url%/}/api/internal/freeswitch/media-agent/tasks/${task_id}/result" \
    -H "Content-Type: application/json" \
    --data "{\"leaseToken\":\"${lease_token}\",\"success\":${success},\"failureReason\":${reason}}" >/dev/null
}

heartbeat() {
  api -X POST "${api_base_url%/}/api/internal/freeswitch/media-agent/heartbeat?agentVersion=${agent_version}" >/dev/null || true
}

process_task() {
  local task="$1" task_id lease_token target_path source_file temp_wav target_dir error_file
  task_id="$(jq -r '.data.taskId' <<<"${task}")"
  lease_token="$(jq -r '.data.leaseToken' <<<"${task}")"
  target_path="$(jq -r '.data.targetPath' <<<"${task}")"

  if [[ "${target_path}" != "${media_root}/"* ]]; then
    report "${task_id}" "${lease_token}" false "目标路径不在配置的媒体根目录中"
    return
  fi

  source_file="${work_dir}/${task_id}.source"
  temp_wav="${work_dir}/${task_id}.wav"
  error_file="${work_dir}/${task_id}.error"
  target_dir="$(dirname "${target_path}")"

  if ! api -o "${source_file}" "${api_base_url%/}/api/internal/freeswitch/media-agent/tasks/${task_id}/source?leaseToken=${lease_token}"; then
    report "${task_id}" "${lease_token}" false "下载源文件失败"
    return
  fi
  if ! ffmpeg -hide_banner -loglevel error -y -i "${source_file}" -acodec pcm_s16le -ar 8000 -ac 1 "${temp_wav}" 2>"${error_file}"; then
    report "${task_id}" "${lease_token}" false "$(cat "${error_file}")"
    rm -f "${source_file}" "${temp_wav}" "${error_file}"
    return
  fi

  mkdir -p "${target_dir}"
  mv -f "${temp_wav}" "${target_path}"
  chmod 0644 "${target_path}"
  rm -f "${source_file}" "${error_file}"
  report "${task_id}" "${lease_token}" true ""
}

while true; do
  heartbeat
  task="$(api -X POST "${api_base_url%/}/api/internal/freeswitch/media-agent/tasks/claim" 2>/dev/null || true)"
  if [[ "$(jq -r '.data.taskId // empty' <<<"${task}" 2>/dev/null)" != "" ]]; then
    process_task "${task}"
  else
    sleep "${poll_seconds}"
  fi
done
