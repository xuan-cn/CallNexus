package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.domain.AiRealtimeCallSession;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class CallRealtimeAsrControlService {

    private static final String ACTIVE_KEY_PREFIX = "callnexus:call:realtime-asr:";
    private static final Duration ACTIVE_TTL = Duration.ofHours(4);
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Set<String> AI_REALTIME_ACTIVE_STATES = Set.of(
        "INITIALIZING", "LISTENING", "THINKING", "SPEAKING", "TRANSFERRING", "ENDING");
    private static final String LEG_CUSTOMER = "CUSTOMER";
    private static final String LEG_AGENT = "AGENT";
    private static final String EVENT_BODY_HEADER = "CallNexus-Event-Body";

    private final ConcurrentMap<String, RecognitionWatch> recognitionWatches = new ConcurrentHashMap<>();

    private final AiKnowledgeProperties properties;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway telephonyCommandGateway;
    private final CallLegMapper callLegMapper;
    private final AiRealtimeCallSessionMapper realtimeCallSessionMapper;

    public void handle(TelephonyEvent event) {
        if (!Boolean.TRUE.equals(properties.getCallRealtimeAsrEnabled())) {
            return;
        }
        if (EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
            startForBridge(event);
        } else if (EslEventNames.DETECTED_SPEECH.equals(event.eventName())) {
            restartAfterSpeech(event);
        } else if (EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())
            || EslEventNames.CHANNEL_DESTROY.equals(event.eventName())) {
            stopForLeg(event);
        }
    }

    private void startForBridge(TelephonyEvent event) {
        String tenantId = nodeQueryService.findTenantId(event.nodeId());
        if (StringUtils.isBlank(tenantId)) {
            log.debug("Skip realtime ASR start without tenant, nodeId={}, uuid={}", event.nodeId(), event.uuid());
            return;
        }
        TenantHelper.dynamic(tenantId, () -> startForBridgeInTenant(event));
    }

    private void startForBridgeInTenant(TelephonyEvent event) {
        EslEndpoint endpoint = endpoint(event.nodeId());
        Set<String> uuids = bridgeUuids(event);
        for (String uuid : uuids) {
            tryStartLeg(endpoint, event.nodeId(), uuid, false);
        }
    }

    private void restartAfterSpeech(TelephonyEvent event) {
        if (!isUuid(event.uuid())) {
            return;
        }
        touchWatch(event.nodeId(), event.uuid());
        if (!hasRecognitionResult(event.headers())) {
            return;
        }
        String key = activeKey(event.uuid());
        if (!RedisUtils.isExistsObject(key)) {
            log.debug("Realtime call ASR restart without active marker, nodeId={}, legUuid={}",
                event.nodeId(), event.uuid());
        }
        String tenantId = nodeQueryService.findTenantId(event.nodeId());
        if (StringUtils.isBlank(tenantId)) {
            log.info("Realtime call ASR restart skipped, reason=NO_TENANT, nodeId={}, legUuid={}",
                event.nodeId(), event.uuid());
            return;
        }
        TenantHelper.dynamic(tenantId, () -> tryStartLeg(endpoint(event.nodeId()), event.nodeId(), event.uuid(), true));
    }

    private void tryStartLeg(EslEndpoint endpoint, Long nodeId, String legUuid, boolean forceRestart) {
        if (!isLiveChannel(endpoint, nodeId, legUuid, forceRestart)) {
            RedisUtils.deleteObject(activeKey(legUuid));
            return;
        }
        CallLeg leg = callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getNodeId, nodeId)
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
        if (leg == null) {
            logSkipIfRestart(forceRestart, "LEG_NOT_FOUND", nodeId, legUuid, null);
            return;
        }
        if (!isActiveLeg(leg)) {
            logSkipIfRestart(forceRestart, "LEG_NOT_ACTIVE", nodeId, legUuid, leg);
            return;
        }
        if (!shouldStartForRole(leg.getLegRole())) {
            logSkipIfRestart(forceRestart, "LEG_ROLE_DISABLED", nodeId, legUuid, leg);
            return;
        }
        if (isAiRealtimeLeg(leg)) {
            logSkipIfRestart(forceRestart, "AI_REALTIME_LEG", nodeId, legUuid, leg);
            return;
        }
        String key = activeKey(legUuid);
        if (!forceRestart && RedisUtils.isExistsObject(key)) {
            RedisUtils.expire(key, ACTIVE_TTL);
            return;
        }
        try {
            AiKnowledgeProperties.UniMrcp unimrcp = properties.getUnimrcp();
            telephonyCommandGateway.startSpeechRecognition(endpoint, legUuid,
                unimrcp.getProfile(), unimrcp.getGrammar(), unimrcp.getDetectScript());
            RedisUtils.setCacheObject(key, leg.getBusinessCallId(), ACTIVE_TTL);
            recognitionWatches.put(watchKey(nodeId, legUuid),
                new RecognitionWatch(nodeId, legUuid, System.currentTimeMillis()));
            log.info("Realtime call ASR {}, businessCallId={}, legUuid={}, legRole={}",
                forceRestart ? "restarted" : "started", leg.getBusinessCallId(), legUuid, leg.getLegRole());
        } catch (Exception exception) {
            log.warn("Realtime call ASR {} failed, businessCallId={}, legUuid={}, error={}",
                forceRestart ? "restart" : "start", leg.getBusinessCallId(), legUuid, exception.getMessage(), exception);
        }
    }

    private boolean isLiveChannel(EslEndpoint endpoint, Long nodeId, String legUuid, boolean forceRestart) {
        try {
            boolean exists = telephonyCommandGateway.callExists(endpoint, legUuid);
            if (!exists) {
                logSkipIfRestart(forceRestart, "CHANNEL_NOT_EXISTS", nodeId, legUuid, null);
            }
            return exists;
        } catch (Exception exception) {
            log.warn("Realtime call ASR channel probe failed, nodeId={}, legUuid={}, error={}",
                nodeId, legUuid, exception.getMessage());
            return false;
        }
    }

    private void logSkipIfRestart(boolean forceRestart, String reason, Long nodeId, String legUuid, CallLeg leg) {
        if (!forceRestart) {
            return;
        }
        log.info("Realtime call ASR restart skipped, reason={}, nodeId={}, legUuid={}, businessCallId={}, legRole={}, legState={}, active={}",
            reason, nodeId, legUuid, leg == null ? null : leg.getBusinessCallId(), leg == null ? null : leg.getLegRole(),
            leg == null ? null : leg.getLegState(), leg == null ? null : leg.getActive());
    }

    private boolean isActiveLeg(CallLeg leg) {
        if (Boolean.FALSE.equals(leg.getActive())) {
            return false;
        }
        return !"ENDED".equalsIgnoreCase(StringUtils.defaultString(leg.getLegState()));
    }

    private void stopForLeg(TelephonyEvent event) {
        if (!isUuid(event.uuid())) {
            return;
        }
        String key = activeKey(event.uuid());
        if (!RedisUtils.isExistsObject(key)) {
            return;
        }
        try {
            telephonyCommandGateway.stopSpeechRecognition(endpoint(event.nodeId()), event.uuid());
        } catch (Exception exception) {
            log.debug("Realtime call ASR stop ignored, nodeId={}, uuid={}, error={}",
                event.nodeId(), event.uuid(), exception.getMessage());
        } finally {
            RedisUtils.deleteObject(key);
            recognitionWatches.remove(watchKey(event.nodeId(), event.uuid()));
        }
    }

    @Scheduled(fixedDelayString = "${ai.unimrcp.channel-probe-interval-ms:2000}")
    public void restartStalledRecognitions() {
        if (!Boolean.TRUE.equals(properties.getCallRealtimeAsrEnabled())) {
            recognitionWatches.clear();
            return;
        }
        long timeoutMs = Math.max(10000L, properties.getUnimrcp().getRecognizeStallTimeoutMs());
        long now = System.currentTimeMillis();
        for (Map.Entry<String, RecognitionWatch> entry : recognitionWatches.entrySet()) {
            RecognitionWatch watch = entry.getValue();
            if (now - watch.lastActivityAt() < timeoutMs) {
                continue;
            }
            RecognitionWatch claimed = new RecognitionWatch(watch.nodeId(), watch.legUuid(), now);
            if (!recognitionWatches.replace(entry.getKey(), watch, claimed)) {
                continue;
            }
            restartStalledRecognition(entry.getKey(), claimed, timeoutMs);
        }
    }

    private void restartStalledRecognition(String key, RecognitionWatch watch, long timeoutMs) {
        EslEndpoint endpoint;
        try {
            endpoint = endpoint(watch.nodeId());
            if (!isLiveChannel(endpoint, watch.nodeId(), watch.legUuid(), true)) {
                recognitionWatches.remove(key);
                RedisUtils.deleteObject(activeKey(watch.legUuid()));
                return;
            }
            log.warn("Realtime call ASR stalled, restart recognition, nodeId={}, legUuid={}, stallTimeoutMs={}",
                watch.nodeId(), watch.legUuid(), timeoutMs);
            telephonyCommandGateway.stopSpeechRecognition(endpoint, watch.legUuid());
            long retryDelayMs = Math.max(100L, properties.getUnimrcp().getRecognizeRetryDelayMs());
            Thread.sleep(Math.min(retryDelayMs, 1000L));
            tryStartLeg(endpoint, watch.nodeId(), watch.legUuid(), true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.warn("Realtime call ASR watchdog restart failed, nodeId={}, legUuid={}, error={}",
                watch.nodeId(), watch.legUuid(), exception.getMessage());
        }
    }

    private void touchWatch(Long nodeId, String legUuid) {
        String key = watchKey(nodeId, legUuid);
        recognitionWatches.computeIfPresent(key,
            (ignored, current) -> new RecognitionWatch(current.nodeId(), current.legUuid(), System.currentTimeMillis()));
    }

    private boolean shouldStartForRole(String legRole) {
        String legs = StringUtils.defaultIfBlank(properties.getCallRealtimeAsrLegs(), "BOTH").trim().toUpperCase();
        if ("BOTH".equals(legs)) {
            return LEG_CUSTOMER.equals(legRole) || LEG_AGENT.equals(legRole);
        }
        if ("CUSTOMER".equals(legs)) {
            return LEG_CUSTOMER.equals(legRole);
        }
        if ("AGENT".equals(legs)) {
            return LEG_AGENT.equals(legRole);
        }
        log.warn("Unknown call realtime ASR legs config={}, fallback to BOTH", legs);
        return LEG_CUSTOMER.equals(legRole) || LEG_AGENT.equals(legRole);
    }

    private boolean isAiRealtimeLeg(CallLeg leg) {
        if (StringUtils.isBlank(leg.getBusinessCallId()) || StringUtils.isBlank(leg.getLegUuid())) {
            return false;
        }
        return realtimeCallSessionMapper.exists(new LambdaQueryWrapper<AiRealtimeCallSession>()
            .eq(AiRealtimeCallSession::getBusinessCallId, leg.getBusinessCallId())
            .eq(AiRealtimeCallSession::getCustomerLegUuid, leg.getLegUuid())
            .in(AiRealtimeCallSession::getSessionState, AI_REALTIME_ACTIVE_STATES));
    }

    private Set<String> bridgeUuids(TelephonyEvent event) {
        Set<String> uuids = new LinkedHashSet<>();
        addUuid(uuids, event.uuid());
        addUuid(uuids, event.headers().get(EslHeaders.OTHER_LEG_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.BRIDGE_A_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.BRIDGE_B_UNIQUE_ID));
        return uuids;
    }

    private void addUuid(Set<String> uuids, String uuid) {
        if (isUuid(uuid)) {
            uuids.add(uuid);
        }
    }

    private boolean hasRecognitionResult(Map<String, String> headers) {
        return StringUtils.isNotBlank(firstNonBlank(
            header(headers, "variable_detect_speech_result"),
            header(headers, "variable_speech_result"),
            header(headers, "Detect-Speech-Result"),
            header(headers, "Speech-Result"),
            header(headers, "detect_speech_result"),
            header(headers, "speech_result")
        )) || StringUtils.containsIgnoreCase(header(headers, EVENT_BODY_HEADER), "<input");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null || StringUtils.isBlank(name)) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        return headers.entrySet().stream()
            .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    private boolean isUuid(String uuid) {
        return StringUtils.isNotBlank(uuid) && UUID_PATTERN.matcher(uuid).matches();
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

    private String activeKey(String uuid) {
        return ACTIVE_KEY_PREFIX + uuid;
    }

    private String watchKey(Long nodeId, String uuid) {
        return nodeId + ":" + uuid;
    }

    private record RecognitionWatch(Long nodeId, String legUuid, long lastActivityAt) {
    }
}
