package org.dromara.ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@RequiredArgsConstructor
public class AiKnowledgeExecutorConfig {
    private final AiKnowledgeProperties properties;

    @Bean(name = "aiKnowledgeTaskExecutor")
    public Executor aiKnowledgeTaskExecutor() {
        int concurrency = Math.max(1, properties.getIndexWorkerConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-knowledge-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
