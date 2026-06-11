package org.dromara.call.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.response.CallRealtimeMessage;
import org.dromara.call.service.TelephonyEventHandler;
import org.dromara.call.service.CallRecordApplicationService;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.websocket.dto.WebSocketMessageDto;
import org.dromara.common.websocket.utils.WebSocketUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelephonyEventHandlerImpl implements TelephonyEventHandler {
    private static final String ACTIVE_CALL_KEY_PREFIX = "callnexus:agent:active-call:";
    private static final String CALL_UUID_TARGETS_KEY_PREFIX = "callnexus:call:uuid-targets-v2:";
    private static final String ENDED_CALL_UUID_KEY_PREFIX = "callnexus:call:ended-uuid:";
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";
    private static final Duration ACTIVE_CALL_TTL = Duration.ofHours(4);
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);
    private static final Duration ENDED_CALL_TTL = Duration.ofSeconds(30);

    private final AgentRealtimeQueryService agentQueryService;
    private final CallRecordApplicationService callRecordApplicationService;

    @Override
    public void onEvent(TelephonyEvent event) {
        try {
            callRecordApplicationService.handleEvent(event);
        } catch (Exception exception) {
            log.error("通话记录事件落库失败，不影响实时通话状态处理，nodeId={}，eventName={}，uuid={}",
                event.nodeId(), event.eventName(), event.uuid(), exception);
        }
        if (!EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName()) && isEndedCallEvent(event)) {
            return;
        }
        Map<Long, AgentRealtimeTargetResponse> targets = resolveTargets(event);
        mergeMappedTargets(event, targets);
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            log.info("Processing FreeSWITCH hangup event, uuid={}, relatedUuids={}, matchedAgents={}, cause={}",
                event.uuid(), relatedUuids(event), targets.keySet(), event.hangupCause());
        }
        for (AgentRealtimeTargetResponse target : targets.values()) {
            TenantHelper.dynamic(target.getTenantId(), () -> updateTargetState(event, target));
            WebSocketMessageDto message = new WebSocketMessageDto();
            message.setSessionKeys(List.of(target.getUserId()));
            message.setMessage(JsonUtils.toJsonString(toMessage(event, target)));
            WebSocketUtils.publishMessage(message);
        }
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            markCallEnded(event);
            deleteUuidMappings(event);
        } else if (!targets.isEmpty()) {
            saveUuidMappings(event, targets.values());
        }
    }

    private void updateTargetState(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            RedisUtils.deleteObject(activeCallKey(target));
            updatePresence(target, AgentPresenceStatus.AFTER_CALL);
        } else {
            saveActiveCallIfAbsent(event, target);
            updatePresence(target, AgentPresenceStatus.BUSY);
        }
    }

    private Map<Long, AgentRealtimeTargetResponse> resolveTargets(TelephonyEvent event) {
        Map<Long, AgentRealtimeTargetResponse> targets = new LinkedHashMap<>();
        AgentRealtimeTargetResponse calledAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.destinationNumber());
        AgentRealtimeTargetResponse callingAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.callerNumber());
        if (calledAgent != null) targets.put(calledAgent.getAgentId(), calledAgent);
        if (callingAgent != null) targets.put(callingAgent.getAgentId(), callingAgent);
        return targets;
    }

    private CallRealtimeMessage toMessage(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        CallRealtimeMessage message = new CallRealtimeMessage();
        message.setType("CALL_" + event.eventName().replace("CHANNEL_", ""));
        message.setCallId(event.uuid());
        message.setCallerNumber(event.callerNumber());
        message.setCalledNumber(event.destinationNumber());
        message.setAgentExtension(target.getExtension());
        message.setHangupCause(event.hangupCause());
        message.setOccurredAt(LocalDateTime.now());
        return message;
    }

    private String activeCallKey(AgentRealtimeTargetResponse target) {
        return ACTIVE_CALL_KEY_PREFIX + target.getTenantId() + ":" + target.getAgentId();
    }

    private void mergeMappedTargets(TelephonyEvent event, Map<Long, AgentRealtimeTargetResponse> targets) {
        for (String uuid : relatedUuids(event)) {
            List<AgentRealtimeTargetResponse> mappedTargets = readMappedTargets(uuid);
            if (mappedTargets != null) {
                mappedTargets.forEach(target -> targets.put(target.getAgentId(), target));
            }
        }
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName()) && targets.isEmpty()) {
            mergeActiveCallTargets(event, targets);
        }
    }

    private void saveUuidMappings(TelephonyEvent event, java.util.Collection<AgentRealtimeTargetResponse> targets) {
        List<AgentRealtimeTargetResponse> snapshot = List.copyOf(targets);
        for (String uuid : relatedUuids(event)) {
            List<AgentRealtimeTargetResponse> existing = readMappedTargets(uuid);
            Map<Long, AgentRealtimeTargetResponse> merged = new LinkedHashMap<>();
            if (existing != null) existing.forEach(target -> merged.put(target.getAgentId(), target));
            snapshot.forEach(target -> merged.put(target.getAgentId(), target));
            RedisUtils.setCacheObject(uuidTargetsKey(uuid), JsonUtils.toJsonString(merged.values()), ACTIVE_CALL_TTL);
        }
    }

    private List<AgentRealtimeTargetResponse> readMappedTargets(String uuid) {
        String json = RedisUtils.getCacheObject(uuidTargetsKey(uuid));
        return json == null ? null : JsonUtils.parseArray(json, AgentRealtimeTargetResponse.class);
    }

    private void deleteUuidMappings(TelephonyEvent event) {
        relatedUuids(event).forEach(uuid -> RedisUtils.deleteObject(uuidTargetsKey(uuid)));
    }

    private Set<String> relatedUuids(TelephonyEvent event) {
        Set<String> uuids = new LinkedHashSet<>();
        addUuid(uuids, event.uuid());
        addUuid(uuids, event.headers().get(EslHeaders.OTHER_LEG_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.BRIDGE_A_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.BRIDGE_B_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.CHANNEL_CALL_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_ORIGINATION_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_BRIDGE_UUID));
        return uuids;
    }

    private void addUuid(Set<String> uuids, String uuid) {
        if (uuid != null && !uuid.isBlank()) uuids.add(uuid);
    }

    private String uuidTargetsKey(String uuid) {
        return CALL_UUID_TARGETS_KEY_PREFIX + uuid;
    }

    private boolean isEndedCallEvent(TelephonyEvent event) {
        return relatedUuids(event).stream().anyMatch(uuid -> RedisUtils.hasKey(endedUuidKey(uuid)));
    }

    private void markCallEnded(TelephonyEvent event) {
        relatedUuids(event).forEach(uuid -> RedisUtils.setCacheObject(endedUuidKey(uuid), Boolean.TRUE, ENDED_CALL_TTL));
    }

    private String endedUuidKey(String uuid) {
        return ENDED_CALL_UUID_KEY_PREFIX + uuid;
    }

    private void mergeActiveCallTargets(TelephonyEvent event, Map<Long, AgentRealtimeTargetResponse> targets) {
        Collection<String> keys = RedisUtils.keys(ACTIVE_CALL_KEY_PREFIX + "*");
        Set<String> relatedUuids = relatedUuids(event);
        for (String key : keys) {
            AgentActiveCall call = RedisUtils.getCacheObject(key);
            if (call == null || !matchesEndedCall(event, relatedUuids, call)) continue;
            AgentRealtimeTargetResponse target = agentQueryService.findByNodeAndExtension(event.nodeId(), call.getAgentExtension());
            if (target != null) targets.put(target.getAgentId(), target);
        }
    }

    private boolean matchesEndedCall(TelephonyEvent event, Set<String> relatedUuids, AgentActiveCall call) {
        if (call.getCallId() != null && relatedUuids.contains(call.getCallId())) return true;
        return equalsAny(call.getAgentExtension(), event.callerNumber(), event.destinationNumber());
    }

    private boolean equalsAny(String source, String... values) {
        if (source == null || source.isBlank()) return false;
        for (String value : values) {
            if (source.equals(value)) return true;
        }
        return false;
    }

    private void saveActiveCallIfAbsent(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        String key = activeCallKey(target);
        if (RedisUtils.getCacheObject(key) != null) return;
        AgentActiveCall call = new AgentActiveCall();
        call.setCallId(event.uuid());
        call.setAgentId(target.getAgentId());
        call.setAgentExtension(target.getExtension());
        call.setDestination(target.getExtension().equals(event.callerNumber()) ? event.destinationNumber() : event.callerNumber());
        RedisUtils.setCacheObject(key, call, ACTIVE_CALL_TTL);
    }

    private void updatePresence(AgentRealtimeTargetResponse target, AgentPresenceStatus status) {
        String key = PRESENCE_KEY_PREFIX + target.getTenantId() + ":" + target.getAgentId();
        AgentPresence presence = RedisUtils.getCacheObject(key);
        if (presence == null) return;
        presence.setStatus(status);
        presence.setUpdatedAt(LocalDateTime.now());
        RedisUtils.setCacheObject(key, presence, PRESENCE_TTL);
    }
}
