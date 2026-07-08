package org.dromara.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Slf4j
public class StreamingTtsProviderRegistry {
    private final Map<String, StreamingTtsProvider> providers = new HashMap<>();

    public StreamingTtsProviderRegistry(List<StreamingTtsProvider> providerList) {
        for (StreamingTtsProvider provider : providerList) {
            String type = normalize(provider.providerType());
            if (providers.put(type, provider) != null) {
                throw new IllegalStateException("流式 TTS Provider 类型重复：" + type);
            }
            log.info("已注册流式 TTS Provider，type={}", type);
        }
    }

    public StreamingTtsProvider get(String providerType) {
        StreamingTtsProvider provider = providers.get(normalize(providerType));
        if (provider == null) {
            throw new ServiceException("不支持的流式 TTS Provider 类型：" + providerType);
        }
        return provider;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
