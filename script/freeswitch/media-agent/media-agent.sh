#!/usr/bin/env bash
set -uo pipefail

api_base_url="${CALLNEXUS_API_BASE_URL:?CALLNEXUS_API_BASE_URL is required}"
node_code="${CALLNEXUS_NODE_CODE:?CALLNEXUS_NODE_CODE is required}"
node_token="${CALLNEXUS_NODE_TOKEN:?CALLNEXUS_NODE_TOKEN is required}"
media_root="${CALLNEXUS_MEDIA_ROOT:-/var/lib/freeswitch/sounds/callnexus}"
poll_seconds="${CALLNEXUS_POLL_SECONDS:-10}"
agent_version="${CALLNEXUS_AGENT_VERSION:-1.0.0}"
work_dir="${CALLNEXUS_AGENT_WORK_DIR:-/tmp/callnexus-media-agent}"
sip_security_enabled="${CALLNEXUS_SIP_SECURITY_ENABLED:-false}"
sip_ports="${CALLNEXUS_SIP_PORTS:-5060,5080}"

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

  if ! mkdir -p "${target_dir}" 2>"${error_file}"; then
    report "${task_id}" "${lease_token}" false "创建目标目录失败：$(cat "${error_file}")"
    rm -f "${source_file}" "${temp_wav}" "${error_file}"
    return
  fi
  if ! mv -f "${temp_wav}" "${target_path}" 2>"${error_file}"; then
    report "${task_id}" "${lease_token}" false "写入目标文件失败：$(cat "${error_file}")"
    rm -f "${source_file}" "${temp_wav}" "${error_file}"
    return
  fi
  if ! chmod 0644 "${target_path}" 2>"${error_file}"; then
    report "${task_id}" "${lease_token}" false "设置目标文件权限失败：$(cat "${error_file}")"
    rm -f "${source_file}" "${error_file}"
    return
  fi
  rm -f "${source_file}" "${error_file}"
  report "${task_id}" "${lease_token}" true ""
}

security_report() {
  local task_id="$1" lease_token="$2" success="$3" reason="${4:-}"
  reason="$(printf '%s' "${reason}" | tail -c 900 | jq -Rs .)"
  api -X POST "${api_base_url%/}/api/internal/freeswitch/sip-registration-security/tasks/${task_id}/result" \
    -H "Content-Type: application/json" \
    --data "{\"leaseToken\":\"${lease_token}\",\"success\":${success},\"failureReason\":${reason}}" >/dev/null
}

security_iptables() {
  local action="$1" source_ip="$2" command="iptables"
  [[ "${source_ip}" == *:* ]] && command="ip6tables"
  if [[ "${action}" == "BLOCK_IP" ]]; then
    "${command}" -C INPUT -s "${source_ip}" -p udp -m multiport --dports "${sip_ports}" -j DROP 2>/dev/null || \
      "${command}" -I INPUT 1 -s "${source_ip}" -p udp -m multiport --dports "${sip_ports}" -j DROP
    "${command}" -C INPUT -s "${source_ip}" -p tcp -m multiport --dports "${sip_ports}" -j DROP 2>/dev/null || \
      "${command}" -I INPUT 1 -s "${source_ip}" -p tcp -m multiport --dports "${sip_ports}" -j DROP
  elif [[ "${action}" == "UNBLOCK_IP" ]]; then
    while "${command}" -C INPUT -s "${source_ip}" -p udp -m multiport --dports "${sip_ports}" -j DROP 2>/dev/null; do
      "${command}" -D INPUT -s "${source_ip}" -p udp -m multiport --dports "${sip_ports}" -j DROP
    done
    while "${command}" -C INPUT -s "${source_ip}" -p tcp -m multiport --dports "${sip_ports}" -j DROP 2>/dev/null; do
      "${command}" -D INPUT -s "${source_ip}" -p tcp -m multiport --dports "${sip_ports}" -j DROP
    done
  else
    return 2
  fi
}

process_security_task() {
  local task="$1" task_id lease_token action source_ip error_file
  task_id="$(jq -r '.data.taskId' <<<"${task}")"
  lease_token="$(jq -r '.data.leaseToken' <<<"${task}")"
  action="$(jq -r '.data.action' <<<"${task}")"
  source_ip="$(jq -r '.data.sourceIp' <<<"${task}")"
  error_file="${work_dir}/${task_id}.security.error"
  if security_iptables "${action}" "${source_ip}" 2>"${error_file}"; then
    security_report "${task_id}" "${lease_token}" true ""
  else
    security_report "${task_id}" "${lease_token}" false "$(cat "${error_file}")"
  fi
  rm -f "${error_file}"
}

while true; do
  heartbeat
  processed=false
  task="$(api -X POST "${api_base_url%/}/api/internal/freeswitch/media-agent/tasks/claim" 2>/dev/null || true)"
  if [[ "$(jq -r '.data.taskId // empty' <<<"${task}" 2>/dev/null)" != "" ]]; then
    process_task "${task}"
    processed=true
  fi
  if [[ "${sip_security_enabled,,}" == "true" ]]; then
    security_task="$(api -X POST "${api_base_url%/}/api/internal/freeswitch/sip-registration-security/tasks/claim" 2>/dev/null || true)"
    if [[ "$(jq -r '.data.taskId // empty' <<<"${security_task}" 2>/dev/null)" != "" ]]; then
      process_security_task "${security_task}"
      processed=true
    fi
  fi
  if [[ "${processed}" == "false" ]]; then
    sleep "${poll_seconds}"
  fi
done
