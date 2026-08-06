package org.dromara.openapi.websocket;

import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.openapi.security.OpenApiPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OpenApiEventWebSocketHandler extends TextWebSocketHandler {
    public static final String PRINCIPAL_ATTRIBUTE = "openApiPrincipal";
    public static final String SUBSCRIBED_EVENTS_ATTRIBUTE = "openApiSubscribedEvents";
    private final OpenApiEventWebSocketRegistry registry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        OpenApiPrincipal principal = (OpenApiPrincipal) session.getAttributes().get(PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Authentication context is missing"));
            return;
        }
        registry.add(principal.applicationId(), session);
        Object subscribedEvents = session.getAttributes().get(SUBSCRIBED_EVENTS_ATTRIBUTE);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("type", "connected");
        response.put("application_id", principal.applicationId().toString());
        response.put("occurred_at", LocalDateTime.now());
        response.put("subscribed_events", subscribedEvents == null ? java.util.List.of() : subscribedEvents);
        if (subscribedEvents instanceof java.util.Collection<?> values && values.isEmpty()) {
            response.put("warning", "当前应用未订阅任何事件，请在开放接口应用中选择订阅事件后重新连接");
        }
        session.sendMessage(new TextMessage(JsonUtils.toJsonString(response)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        OpenApiPrincipal principal = (OpenApiPrincipal) session.getAttributes().get(PRINCIPAL_ATTRIBUTE);
        if (principal != null) registry.remove(principal.applicationId(), session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        OpenApiPrincipal principal = (OpenApiPrincipal) session.getAttributes().get(PRINCIPAL_ATTRIBUTE);
        if (principal != null) registry.remove(principal.applicationId(), session);
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }
}
