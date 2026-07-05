package org.dromara.ai.config;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.realtime.AiRealtimeAudioWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.concurrent.Executor;

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

    @org.springframework.context.annotation.Bean("aiRealtimeExecutor")
    public Executor aiRealtimeExecutor(AiKnowledgeProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int workers = properties.getRealtimeWorkerThreads() == null ? 8 : Math.max(2, properties.getRealtimeWorkerThreads());
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers * 2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-realtime-");
        executor.initialize();
        return executor;
    }

    @org.springframework.context.annotation.Bean("aiRealtimeScheduler")
    public ThreadPoolTaskScheduler aiRealtimeScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ai-realtime-timer-");
        scheduler.initialize();
        return scheduler;
    }
}
