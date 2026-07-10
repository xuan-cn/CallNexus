package org.dromara.ai.config;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.realtime.AiRealtimeAudioWebSocketHandler;
import org.dromara.ai.realtime.AiRealtimeTtsStreamWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AiRealtimeWebSocketConfiguration implements WebSocketConfigurer {
    public static final String AUDIO_PATH = "/api/internal/ai/realtime/audio";
    public static final String TTS_STREAM_PATH = "/api/internal/ai/realtime/tts-stream";
    private final AiRealtimeAudioWebSocketHandler handler;
    private final AiRealtimeTtsStreamWebSocketHandler ttsStreamHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, AUDIO_PATH).setAllowedOrigins("*");
        registry.addHandler(ttsStreamHandler, TTS_STREAM_PATH).setAllowedOrigins("*");
    }
}
