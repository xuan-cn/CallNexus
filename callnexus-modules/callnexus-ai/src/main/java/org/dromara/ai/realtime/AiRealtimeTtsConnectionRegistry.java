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

    private final Map<String, Map<String, Connection>> connections = new ConcurrentHashMap<>();

    public void register(String callId, String turnId, String sessionId, Runnable cancellation) {
        if (StringUtils.isBlank(callId) || StringUtils.isBlank(sessionId) || cancellation == null) {
            return;
        }
        connections.computeIfAbsent(callId, ignored -> new ConcurrentHashMap<>())
            .put(sessionId, new Connection(turnId, cancellation));
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
        Map<String, Connection> sessions = connections.remove(callId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        sessions.forEach((sessionId, connection) -> {
            try {
                connection.cancellation().run();
            } catch (Exception exception) {
                log.debug("取消 AI 实时 TTS WS 失败，callId={}，sessionId={}，error={}",
                    callId, sessionId, exception.getMessage());
            }
        });
        return sessions.size();
    }

    public int cancelByCallIdAndTurnId(String callId, String turnId) {
        if (StringUtils.isBlank(callId) || StringUtils.isBlank(turnId)) {
            return 0;
        }
        Map<String, Connection> sessions = connections.get(callId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (Map.Entry<String, Connection> entry : sessions.entrySet()) {
            Connection connection = entry.getValue();
            if (!turnId.equals(connection.turnId()) || !sessions.remove(entry.getKey(), connection)) {
                continue;
            }
            try {
                connection.cancellation().run();
            } catch (Exception exception) {
                log.debug("取消 AI 实时 TTS turn 失败，callId={}，turnId={}，sessionId={}，error={}",
                    callId, turnId, entry.getKey(), exception.getMessage());
            }
            cancelled++;
        }
        if (sessions.isEmpty()) {
            connections.remove(callId, sessions);
        }
        return cancelled;
    }

    private record Connection(String turnId, Runnable cancellation) {
    }
}
