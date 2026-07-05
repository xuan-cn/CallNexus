package org.dromara.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiKnowledgeProperties {
    private String knowledgeOssConfigKey = "knowledge-document";
    private String qdrantUrl = "http://127.0.0.1:6333";
    private String qdrantApiKey;
    private Integer indexWorkerConcurrency = 2;
    private Integer indexLeaseMinutes = 30;
    private Integer maxDocumentSizeMb = 50;
    private Integer maxChunkCountPerDocument = 20000;
    private Long chatStreamTimeoutSeconds = 120L;
    private Boolean realtimeEnabled = false;
    private String realtimeWebsocketUrl = "ws://127.0.0.1:8080/api/internal/ai/realtime/audio";
    private String realtimeTokenSecret;
    private Integer realtimeTokenTtlSeconds = 300;
    private Integer realtimeWorkerThreads = 8;
}
