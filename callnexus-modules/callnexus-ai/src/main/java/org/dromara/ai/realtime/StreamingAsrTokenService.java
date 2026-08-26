package org.dromara.ai.realtime;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class StreamingAsrTokenService {
    private final AiKnowledgeProperties properties;
    private final RealtimeAudioTokenCodec tokenCodec;

    public String issue(String tenantId, Long nodeId, String businessCallId, String legUuid, String speaker) {
        int ttl = properties.getRealtimeTokenTtlSeconds() == null ? 300 : properties.getRealtimeTokenTtlSeconds();
        return tokenCodec.issue(new StreamingAsrClaims(tenantId, nodeId, businessCallId, legUuid, speaker,
            Instant.now().getEpochSecond() + Math.max(60, ttl)));
    }

    public StreamingAsrClaims verify(String token) {
        StreamingAsrClaims claims = tokenCodec.verify(token, StreamingAsrClaims.class,
            "流式 ASR 令牌缺失", "流式 ASR 令牌格式或签名无效");
        if (claims.expiresAt() < Instant.now().getEpochSecond()) {
            throw new ServiceException("流式 ASR 令牌已过期");
        }
        return claims;
    }
}
