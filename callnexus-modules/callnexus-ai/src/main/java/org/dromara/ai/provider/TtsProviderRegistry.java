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
public class TtsProviderRegistry {
    private final Map<String, TtsProvider> providers = new HashMap<>();

    public TtsProviderRegistry(List<TtsProvider> providerList) {
        for (TtsProvider provider : providerList) {
            String type = normalize(provider.providerType());
            if (providers.put(type, provider) != null) {
                throw new IllegalStateException("TTS Provider 类型重复：" + type);
            }
            log.info("已注册 TTS Provider，type={}", type);
        }
    }

    public TtsProvider get(String providerType) {
        TtsProvider provider = providers.get(normalize(providerType));
        if (provider == null) {
            throw new ServiceException("不支持的 TTS Provider 类型：" + providerType);
        }
        return provider;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
