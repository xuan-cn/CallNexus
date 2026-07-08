#!/bin/sh
set -eu

business_call_id="${1:-}"
recording_path="${2:-}"
api_base_url="${CALLNEXUS_API_BASE_URL:-http://192.168.1.121:8080}"
tenant_id="${CALLNEXUS_TENANT_ID:-000000}"
token="${CALLNEXUS_FREESWITCH_DIRECTORY_SECRET:-}"
log_file="${CALLNEXUS_RECORDING_UPLOAD_LOG:-/tmp/callnexus-recording-upload.log}"

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "${log_file}" 2>/dev/null || true
}

if ! printf '%s' "${business_call_id}" | grep -Eq '^[0-9a-fA-F-]{36}$' \
  || [ -z "${recording_path}" ] || [ -z "${token}" ]; then
  log "录音上传参数无效或鉴权 Token 未配置，businessCallId=${business_call_id}，recordingPath=${recording_path}，tokenConfigured=$([ -n "${token}" ] && printf true || printf false)"
  exit 2
fi

attempt=0
while [ "${attempt}" -lt 20 ]; do
  [ -s "${recording_path}" ] && break
  sleep 1
  attempt=$((attempt + 1))
done

[ -s "${recording_path}" ] || {
  log "录音文件不存在或为空，businessCallId=${business_call_id}，recordingPath=${recording_path}"
  exit 3
}

log "开始上传通话录音，businessCallId=${business_call_id}，recordingPath=${recording_path}"
if ! curl --fail --silent --show-error \
  --retry 5 \
  --retry-all-errors \
  --connect-timeout 10 \
  --max-time 300 \
  -X POST "${api_base_url%/}/api/internal/freeswitch/recordings" \
  -H "X-CallNexus-FreeSWITCH-Token: ${token}" \
  -F "tenantId=${tenant_id}" \
  -F "businessCallId=${business_call_id}" \
  -F "file=@${recording_path};type=audio/wav" >> "${log_file}" 2>&1; then
  log "通话录音上传失败，businessCallId=${business_call_id}，apiBaseUrl=${api_base_url}"
  exit 4
fi
log "通话录音上传成功，businessCallId=${business_call_id}"
