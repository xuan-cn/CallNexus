package org.dromara.ai.service;

import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.Map;

import static org.mockito.Mockito.*;

class AiRealtimeMrcpLifecycleTest {
    @Mock
    private AiKnowledgeProperties properties;
    @Mock
    private FreeSwitchNodeQueryService nodeQueryService;
    @InjectMocks
    private AiRealtimeMrcpEventService service;

    @Test
    void shouldRememberHangupBeforeRuntimeExistsAndRejectLateCompletion() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
             AutoCloseable mocks = MockitoAnnotations.openMocks(this)) {
            context.getBeanFactory().registerSingleton("redissonClient", mock(RedissonClient.class));
            context.refresh();
            new SpringUtils().setApplicationContext(context);
            try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
                when(properties.getRealtimeEnabled()).thenReturn(true);
                when(nodeQueryService.findTenantId(1L)).thenReturn("000000");
                String uuid = "6e28ac20-8b2f-45e2-aae0-98db418505aa";
                String endedKey = "callnexus:call:ended-uuid:" + uuid;

                service.handle(1L, "CHANNEL_HANGUP", uuid, Map.of());
                redis.verify(() -> RedisUtils.setCacheObject(endedKey, Boolean.TRUE, Duration.ofMinutes(5)));
                redis.when(() -> RedisUtils.hasKey(endedKey)).thenReturn(true);

                service.handle(1L, "CHANNEL_EXECUTE_COMPLETE", uuid, Map.of(
                    "variable_callnexus_ai_transport", "UNIMRCP", "Application", "detect_speech"));

                // A late completion must not reacquire ownership or proceed to create a runtime.
                redis.verify(() -> RedisUtils.getCacheObject("callnexus:ai:realtime:owner:" + uuid), never());
            }
        }
    }
}
