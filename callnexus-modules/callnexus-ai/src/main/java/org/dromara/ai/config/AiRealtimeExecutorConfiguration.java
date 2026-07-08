package org.dromara.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
public class AiRealtimeExecutorConfiguration {

    @Bean("aiRealtimeExecutor")
    public Executor aiRealtimeExecutor(AiKnowledgeProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int workers = properties.getRealtimeWorkerThreads() == null
            ? 8
            : Math.max(2, properties.getRealtimeWorkerThreads());
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers * 2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-realtime-");
        executor.initialize();
        return executor;
    }

    @Bean("aiRealtimeScheduler")
    public ThreadPoolTaskScheduler aiRealtimeScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ai-realtime-timer-");
        scheduler.initialize();
        return scheduler;
    }
}
