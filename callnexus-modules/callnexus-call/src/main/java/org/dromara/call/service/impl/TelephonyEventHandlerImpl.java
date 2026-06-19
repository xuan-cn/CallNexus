package org.dromara.call.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.agent.runtime.AgentQueueRuntimeStatus;
import org.dromara.agent.service.CallQueueRuntimeSyncService;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.response.CallRealtimeMessage;
import org.dromara.call.service.TelephonyEventHandler;
import org.dromara.call.service.CallRecordApplicationService;
import org.dromara.call.service.QueueEventApplicationService;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.sse.dto.SseMessageDto;
import org.dromara.common.sse.utils.SseMessageUtils;
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
    private static final String CALL_UUID_ANSWERED_TARGETS_KEY_PREFIX = "callnexus:call:uuid-answered-targets:";
    private static final String ENDED_CALL_UUID_KEY_PREFIX = "callnexus:call:ended-uuid:";
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";
    private static final Duration ACTIVE_CALL_TTL = Duration.ofHours(4);
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);
    private static final Duration ENDED_CALL_TTL = Duration.ofSeconds(30);

    private final AgentRealtimeQueryService agentQueryService;
    private final CallQueueRuntimeSyncService queueRuntimeSyncService;
    private final CallRecordApplicationService callRecordApplicationService;
    private final QueueEventApplicationService queueEventApplicationService;

    @Override
    public void onEvent(TelephonyEvent event) {
        // mod_callcenter 队列事件走独立的队列事件处理服务，不参与坐席实时状态机和 WebSocket 推送。
        if (EslEventNames.CUSTOM.equals(event.eventName())) {
            if (!EslEventNames.isCallCenterQueueEvent(event.eventName(), event.eventSubclass())) {
                return;
            }
            try {
                queueEventApplicationService.handleQueueEvent(event);
            } catch (Exception exception) {
                log.error("队列事件落库失败，不影响坐席实时状态处理，nodeId={}，subclass={}，uuid={}",
                    event.nodeId(), event.eventSubclass(), event.uuid(), exception);
            }
            publishCallCenterAgentRealtimeEvent(event);
            return;
        }
        try {
            callRecordApplicationService.handleEvent(event);
        } catch (Exception exception) {
            log.error("通话记录事件落库失败，不影响实时通话状态处理，nodeId={}，eventName={}，uuid={}",
                event.nodeId(), event.eventName(), event.uuid(), exception);
        }
        if (EslEventNames.isTerminalEvent(event.eventName())
            && !EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            return;
        }
        if (!EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName()) && isEndedCallEvent(event)) {
            return;
        }
        Map<Long, AgentRealtimeTargetResponse> targets = resolveTargets(event);
        mergeMappedTargets(event, targets);
        if (isConnectedEvent(event)) {
            saveAnsweredTargets(event, targets.values());
        }
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            log.info("Processing FreeSWITCH hangup event, uuid={}, relatedUuids={}, matchedAgents={}, cause={}",
                event.uuid(), relatedUuids(event), targets.keySet(), event.hangupCause());
        }
        if (targets.isEmpty()) {
            log.debug("通话实时事件未匹配到坐席，不推送前端，nodeId={}，eventName={}，uuid={}，callerNumber={}，destinationNumber={}",
                event.nodeId(), event.eventName(), event.uuid(), event.callerNumber(), event.destinationNumber());
        }
        for (AgentRealtimeTargetResponse target : targets.values()) {
            if (isStaleMappedHangupForTarget(event, target)) {
                log.debug("忽略历史映射带出的非当前坐席挂断事件，uuid={}，targetExtension={}，callerNumber={}，destinationNumber={}",
                    event.uuid(), target.getExtension(), event.callerNumber(), event.destinationNumber());
                continue;
            }
            TenantHelper.dynamic(target.getTenantId(), () -> updateTargetState(event, target));
            String realtimeMessage = JsonUtils.toJsonString(toMessage(event, target));
            publishRealtimeMessage(target.getUserId(), realtimeMessage);
        }
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            markCallEnded(event);
            deleteUuidMappings(event);
            deleteAnsweredTargetMappings(event);
        } else if (!targets.isEmpty()) {
            saveUuidMappings(event, targets.values());
        }
    }

    private void publishRealtimeMessage(Long userId, String realtimeMessage) {
        WebSocketMessageDto webSocketMessage = new WebSocketMessageDto();
        webSocketMessage.setSessionKeys(List.of(userId));
        webSocketMessage.setMessage(realtimeMessage);
        WebSocketUtils.publishMessage(webSocketMessage);

        SseMessageDto sseMessage = new SseMessageDto();
        sseMessage.setUserIds(List.of(userId));
        sseMessage.setMessage(realtimeMessage);
        SseMessageUtils.publishMessage(sseMessage);
    }

    private void updateTargetState(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            RedisUtils.deleteObject(activeCallKey(target));
            // 已接听的坐席挂断后进入话后整理，把本次通话 channel UUID 记录到 presence，
            // 供话后整理时长按实际接听队列计算；整理结束恢复 IDLE 时清空。
            String handlingCallId = wasAnswered(event, target) ? event.uuid() : null;
            updatePresence(target, wasAnswered(event, target) ? AgentPresenceStatus.AFTER_CALL : AgentPresenceStatus.IDLE, handlingCallId);
        } else if (isConnectedEvent(event)) {
            saveActiveCallIfAbsent(event, target);
            updatePresence(target, AgentPresenceStatus.BUSY, null);
            // CHANNEL_BRIDGE 时记录队列接听节点（仅对队列来电生效，非队列来电直接返回 null）。
            // 触发时机放在坐席状态流转之后，避免阻塞实时状态推送。
            if (EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
                try {
                    queueEventApplicationService.recordAgentAnswerOnBridge(event.uuid());
                } catch (Exception exception) {
                    log.warn("记录队列坐席接听事件失败，不影响实时通话状态，uuid={}", event.uuid(), exception);
                }
            }
        }
    }

    private boolean isConnectedEvent(TelephonyEvent event) {
        return EslEventNames.CHANNEL_ANSWER.equals(event.eventName())
            || EslEventNames.CHANNEL_BRIDGE.equals(event.eventName());
    }

    private boolean isStaleMappedHangupForTarget(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        if (!EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            return false;
        }
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(target));
        if (activeCall != null && matchesEndedCall(event, relatedUuids(event), activeCall)) {
            return false;
        }
        String extension = normalizeExtension(target.getExtension());
        if (extension == null) {
            return false;
        }
        return !extension.equals(normalizeExtension(event.callerNumber()))
            && !extension.equals(normalizeExtension(event.destinationNumber()));
    }

    private Map<Long, AgentRealtimeTargetResponse> resolveTargets(TelephonyEvent event) {
        Map<Long, AgentRealtimeTargetResponse> targets = new LinkedHashMap<>();
        addTargetByExtension(targets, event.nodeId(), event.destinationNumber());
        addTargetByExtension(targets, event.nodeId(), event.callerNumber());
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.CALLER_CALLEE_ID_NUMBER));
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.VARIABLE_SIP_TO_USER));
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.VARIABLE_SIP_REQ_USER));
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.VARIABLE_DIALED_USER));
        addTargetByExtension(targets, event.nodeId(), event.headers().get(EslHeaders.VARIABLE_DIALLED_USER));
        addTargetByExtension(targets, event.nodeId(), extensionFromDialString(event.headers().get(EslHeaders.VARIABLE_CURRENT_APPLICATION_DATA)));
        return targets;
    }

    private void addTargetByExtension(Map<Long, AgentRealtimeTargetResponse> targets, Long nodeId, String extension) {
        String normalized = normalizeExtension(extension);
        if (normalized == null) return;
        AgentRealtimeTargetResponse target = agentQueryService.findByNodeAndExtension(nodeId, normalized);
        if (target != null) targets.put(target.getAgentId(), target);
    }

    private void publishCallCenterAgentRealtimeEvent(TelephonyEvent event) {
        if (!EslEventNames.SUBCLASS_CC_RING_AGENT.equals(event.eventSubclass())
            && !EslEventNames.SUBCLASS_CC_AGENT_ANSWER.equals(event.eventSubclass())) {
            return;
        }
        String extension = normalizeExtension(event.headers().get(EslHeaders.CC_AGENT));
        if (extension == null) return;
        AgentRealtimeTargetResponse target = agentQueryService.findByNodeAndExtension(event.nodeId(), extension);
        if (target == null) return;
        CallRealtimeMessage message = new CallRealtimeMessage();
        message.setType(EslEventNames.SUBCLASS_CC_AGENT_ANSWER.equals(event.eventSubclass()) ? "CALL_ANSWER" : "CALL_PROGRESS");
        message.setCallId(event.uuid());
        message.setCallerNumber(event.callerNumber());
        message.setCalledNumber(target.getExtension());
        message.setAgentExtension(target.getExtension());
        message.setOccurredAt(LocalDateTime.now());
        publishRealtimeMessage(target.getUserId(), JsonUtils.toJsonString(message));
        if (EslEventNames.SUBCLASS_CC_AGENT_ANSWER.equals(event.eventSubclass())) {
            TenantHelper.dynamic(target.getTenantId(), () -> {
                saveActiveCallIfAbsent(event, target);
                updatePresence(target, AgentPresenceStatus.BUSY, null);
            });
        }
    }

    private String normalizeExtension(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.startsWith("user/")) {
            normalized = normalized.substring("user/".length());
        }
        int atIndex = normalized.indexOf('@');
        if (atIndex > 0) {
            normalized = normalized.substring(0, atIndex);
        }
        normalized = normalized.replaceAll("[^0-9*#+]", "");
        return normalized.isBlank() ? null : normalized;
    }

    private String extensionFromDialString(String value) {
        if (value == null || value.isBlank()) return null;
        int userIndex = value.indexOf("user/");
        if (userIndex < 0) return null;
        return normalizeExtension(value.substring(userIndex + "user/".length()));
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
        if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
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

    private void saveAnsweredTargets(TelephonyEvent event, Collection<AgentRealtimeTargetResponse> targets) {
        List<AgentRealtimeTargetResponse> snapshot = List.copyOf(targets);
        for (String uuid : relatedUuids(event)) {
            List<AgentRealtimeTargetResponse> existing = readAnsweredTargets(uuid);
            Map<Long, AgentRealtimeTargetResponse> merged = new LinkedHashMap<>();
            if (existing != null) existing.forEach(target -> merged.put(target.getAgentId(), target));
            snapshot.forEach(target -> merged.put(target.getAgentId(), target));
            RedisUtils.setCacheObject(answeredTargetsKey(uuid), JsonUtils.toJsonString(merged.values()), ACTIVE_CALL_TTL);
        }
    }

    private List<AgentRealtimeTargetResponse> readAnsweredTargets(String uuid) {
        String json = RedisUtils.getCacheObject(answeredTargetsKey(uuid));
        return json == null ? null : JsonUtils.parseArray(json, AgentRealtimeTargetResponse.class);
    }

    private boolean wasAnswered(TelephonyEvent event, AgentRealtimeTargetResponse target) {
        for (String uuid : relatedUuids(event)) {
            List<AgentRealtimeTargetResponse> answeredTargets = readAnsweredTargets(uuid);
            if (answeredTargets != null && answeredTargets.stream().anyMatch(item -> item.getAgentId().equals(target.getAgentId()))) {
                return true;
            }
        }
        return false;
    }

    private void deleteAnsweredTargetMappings(TelephonyEvent event) {
        relatedUuids(event).forEach(uuid -> RedisUtils.deleteObject(answeredTargetsKey(uuid)));
    }

    private String answeredTargetsKey(String uuid) {
        return CALL_UUID_ANSWERED_TARGETS_KEY_PREFIX + uuid;
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
            if (target != null) {
                if (!target.getAgentId().equals(call.getAgentId())) {
                    log.warn("挂断事件 activeCall 兜底匹配到异常坐席数据，uuid={}，relatedUuids={}，activeAgentId={}，resolvedAgentId={}，extension={}，activeCallId={}",
                        event.uuid(), relatedUuids, call.getAgentId(), target.getAgentId(), call.getAgentExtension(), call.getCallId());
                    continue;
                }
                targets.put(target.getAgentId(), target);
                log.info("挂断事件按 activeCall 兜底匹配到坐席，uuid={}，relatedUuids={}，agentId={}，extension={}，activeCallId={}",
                    event.uuid(), relatedUuids, target.getAgentId(), target.getExtension(), call.getCallId());
            }
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
        AgentActiveCall call = new AgentActiveCall();
        call.setCallId(event.uuid());
        call.setAgentId(target.getAgentId());
        call.setAgentExtension(target.getExtension());
        call.setDestination(target.getExtension().equals(event.callerNumber()) ? event.destinationNumber() : event.callerNumber());
        RedisUtils.setCacheObject(key, call, ACTIVE_CALL_TTL);
    }

    private void updatePresence(AgentRealtimeTargetResponse target, AgentPresenceStatus status, String handlingCallId) {
        String key = PRESENCE_KEY_PREFIX + target.getTenantId() + ":" + target.getAgentId();
        AgentPresence presence = RedisUtils.getCacheObject(key);
        if (presence == null) return;
        presence.setStatus(status);
        presence.setUpdatedAt(LocalDateTime.now());
        // AFTER_CALL 记录本次通话 channel UUID 用于话后整理时长计算；其余状态清空，避免残留。
        presence.setHandlingCallId(AgentPresenceStatus.AFTER_CALL.equals(status) ? handlingCallId : null);
        RedisUtils.setCacheObject(key, presence, PRESENCE_TTL);
        syncQueueStatus(target, status);
    }

    private void syncQueueStatus(AgentRealtimeTargetResponse target, AgentPresenceStatus status) {
        try {
            if (target.getNodeId() == null || target.getSipDomain() == null || target.getSipDomain().isBlank()) return;
            queueRuntimeSyncService.syncAgentStatus(new AgentQueueRuntimeStatus(
                target.getNodeId(), target.getExtension(), target.getSipDomain(), status));
        } catch (Exception exception) {
            log.warn("通话事件同步 FreeSWITCH 队列坐席状态失败，不影响本地坐席状态，agentId={}，status={}，error={}",
                target.getAgentId(), status, exception.getMessage());
        }
    }
}
