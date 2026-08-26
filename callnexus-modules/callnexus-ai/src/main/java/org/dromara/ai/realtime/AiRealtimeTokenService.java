package org.dromara.ai.realtime;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class AiRealtimeTokenService {
    private final AiKnowledgeProperties properties;
    private final RealtimeAudioTokenCodec tokenCodec;

    public String issue(String tenantId, Long agentId, Long flowId, Long nodeId) {
        int ttl = properties.getRealtimeTokenTtlSeconds() == null ? 300 : properties.getRealtimeTokenTtlSeconds();
        AiRealtimeClaims claims = new AiRealtimeClaims(tenantId, agentId, flowId, nodeId,
            Instant.now().getEpochSecond() + Math.max(60, ttl));
        return tokenCodec.issue(claims);
    }

    public AiRealtimeClaims verify(String token) {
        AiRealtimeClaims claims = tokenCodec.verify(token, AiRealtimeClaims.class,
            "AI 实时音频令牌缺失", "AI 实时音频令牌格式或签名无效");
        if (claims.expiresAt() < Instant.now().getEpochSecond()) {
            throw new ServiceException("AI 实时音频令牌已过期");
        }
        return claims;
    }
}
