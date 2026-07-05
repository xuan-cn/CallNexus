package org.dromara.ai.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiRealtimeAudioWebSocketHandler extends AbstractWebSocketHandler {
    private final AiRealtimeSessionManager sessionManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> query = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().toSingleValueMap();
        try {
            sessionManager.connect(session, query.get("token"), query.get("businessCallId"), query.get("customerLegUuid"));
        } catch (Exception exception) {
            log.warn("拒绝 AI 实时音频连接，remote={}，error={}", session.getRemoteAddress(), exception.getMessage());
            session.close(CloseStatus.POLICY_VIOLATION.withReason(StringUtils.blankToDefault(exception.getMessage(), "连接校验失败")));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        byte[] audio = new byte[message.getPayloadLength()];
        message.getPayload().get(audio);
        sessionManager.audio(session.getId(), audio);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        sessionManager.control(session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessionManager.fail(session.getId(), "音频 WebSocket 传输异常：" + exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionManager.disconnect(session.getId(), status.getCode() + " " + status.getReason());
    }
}
