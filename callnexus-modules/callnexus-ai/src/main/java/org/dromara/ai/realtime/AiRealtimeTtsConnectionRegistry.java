package org.dromara.ai.realtime;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks realtime TTS WebSocket connections by business call/channel id.
 */
@Slf4j
@Component
public class AiRealtimeTtsConnectionRegistry {

    private final Map<String, Map<String, Runnable>> connections = new ConcurrentHashMap<>();

    public void register(String callId, String sessionId, Runnable cancellation) {
        if (StringUtils.isBlank(callId) || StringUtils.isBlank(sessionId) || cancellation == null) {
            return;
        }
        connections.computeIfAbsent(callId, ignored -> new ConcurrentHashMap<>())
            .put(sessionId, cancellation);
    }

    public void unregister(String callId, String sessionId) {
        if (StringUtils.isBlank(callId) || StringUtils.isBlank(sessionId)) {
            return;
        }
        connections.computeIfPresent(callId, (ignored, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public int cancelByCallId(String callId) {
        if (StringUtils.isBlank(callId)) {
            return 0;
        }
        Map<String, Runnable> sessions = connections.remove(callId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        sessions.forEach((sessionId, cancellation) -> {
            try {
                cancellation.run();
            } catch (Exception exception) {
                log.debug("取消 AI 实时 TTS WS 失败，callId={}，sessionId={}，error={}",
                    callId, sessionId, exception.getMessage());
            }
        });
        return sessions.size();
    }
}
