package org.dromara.ai.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class RealtimeAudioTokenCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final AiKnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    public String issue(Object claims) {
        requireSecret();
        try {
            String payload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            return payload + "." + sign(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("生成实时音频令牌失败", exception);
        }
    }

    public <T> T verify(String token, Class<T> claimsType, String missingMessage, String invalidMessage) {
        requireSecret();
        if (StringUtils.isBlank(token) || !token.contains(".")) {
            throw new ServiceException(missingMessage);
        }
        String[] parts = token.split("\\.", 2);
        if (!MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.US_ASCII),
            parts[1].getBytes(StandardCharsets.US_ASCII))) {
            throw new ServiceException(invalidMessage);
        }
        try {
            T claims = objectMapper.readValue(DECODER.decode(parts[0]), claimsType);
            if (claims == null) {
                throw new ServiceException(invalidMessage);
            }
            return claims;
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException(invalidMessage);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getRealtimeTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成实时音频令牌失败", exception);
        }
    }

    private void requireSecret() {
        if (StringUtils.isBlank(properties.getRealtimeTokenSecret()) || properties.getRealtimeTokenSecret().length() < 32) {
            throw new ServiceException("请配置至少 32 位的 CALLNEXUS_AI_REALTIME_TOKEN_SECRET");
        }
    }
}
