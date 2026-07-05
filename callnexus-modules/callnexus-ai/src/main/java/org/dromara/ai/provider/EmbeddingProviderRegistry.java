package org.dromara.ai.provider;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EmbeddingProviderRegistry {
    private final Map<String, EmbeddingProvider> providers;

    public EmbeddingProviderRegistry(List<EmbeddingProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
            item -> normalize(item.providerType()), Function.identity(),
            (left, right) -> { throw new IllegalStateException("重复的 Embedding Provider 类型：" + left.providerType()); }));
    }

    public EmbeddingProvider get(String type) {
        EmbeddingProvider provider = providers.get(normalize(type));
        if (provider == null) throw new ServiceException("暂不支持该 Embedding Provider 类型：" + type);
        return provider;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
