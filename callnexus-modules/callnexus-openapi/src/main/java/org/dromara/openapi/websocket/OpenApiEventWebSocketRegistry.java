package org.dromara.openapi.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class OpenApiEventWebSocketRegistry {
    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void add(Long applicationId, WebSocketSession session) {
        sessions.computeIfAbsent(applicationId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void remove(Long applicationId, WebSocketSession session) {
        Set<WebSocketSession> values = sessions.get(applicationId);
        if (values == null) return;
        values.remove(session);
        if (values.isEmpty()) sessions.remove(applicationId, values);
    }

    public void send(Long applicationId, String payload) {
        Set<WebSocketSession> values = sessions.get(applicationId);
        if (values == null) return;
        for (WebSocketSession session : values) {
            if (!session.isOpen()) {
                remove(applicationId, session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException exception) {
                log.warn("OpenAPI WebSocket event push failed, applicationId={}, sessionId={}, error={}",
                    applicationId, session.getId(), exception.getMessage());
                remove(applicationId, session);
            }
        }
    }
}
