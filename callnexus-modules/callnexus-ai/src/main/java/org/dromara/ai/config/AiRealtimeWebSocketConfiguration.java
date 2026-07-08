package org.dromara.ai.config;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.realtime.AiRealtimeAudioWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AiRealtimeWebSocketConfiguration implements WebSocketConfigurer {
    public static final String AUDIO_PATH = "/api/internal/ai/realtime/audio";
    private final AiRealtimeAudioWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, AUDIO_PATH).setAllowedOrigins("*");
    }
}
