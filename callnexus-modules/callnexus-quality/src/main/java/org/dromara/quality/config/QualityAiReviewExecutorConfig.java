package org.dromara.quality.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class QualityAiReviewExecutorConfig {
    @Bean(name = "qualityAiReviewExecutor")
    public Executor qualityAiReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 质检会发送整段转写和完整评分表，默认串行调用，避免低并发模型服务被同时压满后全部超时。
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // 数据库任务表就是持久化队列，线程池只负责当前可立即执行的任务。
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setThreadNamePrefix("quality-ai-review-");
        executor.initialize();
        return executor;
    }
}
