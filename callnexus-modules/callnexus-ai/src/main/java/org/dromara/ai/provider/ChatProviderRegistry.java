package org.dromara.ai.provider;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChatProviderRegistry {
    private final Map<String, ChatProvider> providers;

    public ChatProviderRegistry(List<ChatProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
            item -> normalize(item.providerType()), Function.identity(),
            (left, right) -> { throw new IllegalStateException("重复的 Chat Provider 类型：" + left.providerType()); }));
    }

    public ChatProvider get(String type) {
        ChatProvider provider = providers.get(normalize(type));
        if (provider == null) throw new ServiceException("暂不支持该 Chat Provider 类型：" + type);
        return provider;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
