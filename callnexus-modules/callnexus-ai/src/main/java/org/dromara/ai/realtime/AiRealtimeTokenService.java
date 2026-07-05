package org.dromara.ai.realtime;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class AiRealtimeTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final AiKnowledgeProperties properties;

    public String issue(String tenantId, Long agentId, Long flowId, Long nodeId) {
        requireSecret();
        int ttl = properties.getRealtimeTokenTtlSeconds() == null ? 300 : properties.getRealtimeTokenTtlSeconds();
        AiRealtimeClaims claims = new AiRealtimeClaims(tenantId, agentId, flowId, nodeId,
            Instant.now().getEpochSecond() + Math.max(60, ttl));
        String payload = ENCODER.encodeToString(JsonUtils.toJsonString(claims).getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    public AiRealtimeClaims verify(String token) {
        requireSecret();
        if (StringUtils.isBlank(token) || !token.contains(".")) {
            throw new ServiceException("AI 实时音频令牌缺失");
        }
        String[] parts = token.split("\\.", 2);
        if (!MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.US_ASCII),
            parts[1].getBytes(StandardCharsets.US_ASCII))) {
            throw new ServiceException("AI 实时音频令牌签名无效");
        }
        try {
            AiRealtimeClaims claims = JsonUtils.parseObject(new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8),
                AiRealtimeClaims.class);
            if (claims == null || claims.expiresAt() < Instant.now().getEpochSecond()) {
                throw new ServiceException("AI 实时音频令牌已过期");
            }
            return claims;
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("AI 实时音频令牌格式无效");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getRealtimeTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成 AI 实时音频令牌失败", exception);
        }
    }

    private void requireSecret() {
        if (StringUtils.isBlank(properties.getRealtimeTokenSecret()) || properties.getRealtimeTokenSecret().length() < 32) {
            throw new ServiceException("请配置至少 32 位的 CALLNEXUS_AI_REALTIME_TOKEN_SECRET");
        }
    }
}
