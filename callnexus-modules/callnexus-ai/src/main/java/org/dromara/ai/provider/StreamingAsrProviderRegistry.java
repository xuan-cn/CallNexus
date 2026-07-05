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
public class StreamingAsrProviderRegistry {
    private final Map<String, StreamingAsrProvider> providers = new HashMap<>();

    public StreamingAsrProviderRegistry(List<StreamingAsrProvider> providerList) {
        for (StreamingAsrProvider provider : providerList) {
            String type = normalize(provider.providerType());
            if (providers.put(type, provider) != null) {
                throw new IllegalStateException("流式 ASR Provider 类型重复：" + type);
            }
            log.info("已注册流式 ASR Provider，type={}", type);
        }
    }

    public StreamingAsrProvider get(String providerType) {
        StreamingAsrProvider provider = providers.get(normalize(providerType));
        if (provider == null) {
            throw new ServiceException("不支持的流式 ASR Provider 类型：" + providerType);
        }
        return provider;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
