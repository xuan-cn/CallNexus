package org.dromara.openapi.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OpenApiEventWebSocketTopicListener implements ApplicationRunner, Ordered {
    public static final String TOPIC = "callnexus:openapi:event";
    public static final String INSTANCE_ID = UUID.randomUUID().toString();

    private final OpenApiEventWebSocketRegistry registry;

    @Override
    public void run(ApplicationArguments args) {
        RedisUtils.subscribe(TOPIC, OpenApiEventClusterMessage.class, message -> {
            if (message == null || message.getApplicationId() == null || message.getPayload() == null) return;
            if (INSTANCE_ID.equals(message.getSourceInstanceId())) return;
            registry.send(message.getApplicationId(), message.getPayload());
        });
        log.info("OpenAPI event WebSocket cluster listener initialized, topic={}", TOPIC);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
