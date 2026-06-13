#!/usr/bin/env bash
set -euo pipefail

business_call_id="${1:-}"
recording_path="${2:-}"
api_base_url="${CALLNEXUS_API_BASE_URL:-http://192.168.1.121:8080}"
tenant_id="${CALLNEXUS_TENANT_ID:-000000}"
token="${CALLNEXUS_FREESWITCH_DIRECTORY_SECRET:-}"

if [[ ! "${business_call_id}" =~ ^[0-9a-fA-F-]{36}$ ]] || [[ -z "${recording_path}" ]] || [[ -z "${token}" ]]; then
  exit 2
fi

for _ in {1..20}; do
  [[ -s "${recording_path}" ]] && break
  sleep 1
done

[[ -s "${recording_path}" ]] || exit 3

curl --fail --silent --show-error \
  --retry 5 \
  --retry-all-errors \
  --connect-timeout 10 \
  --max-time 300 \
  -X POST "${api_base_url%/}/api/internal/freeswitch/recordings" \
  -H "X-CallNexus-FreeSWITCH-Token: ${token}" \
  -F "tenantId=${tenant_id}" \
  -F "businessCallId=${business_call_id}" \
  -F "file=@${recording_path};type=audio/wav"
