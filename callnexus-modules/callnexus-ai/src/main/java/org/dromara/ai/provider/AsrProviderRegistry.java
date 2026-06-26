package org.dromara.ai.provider;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AsrProviderRegistry {

    private final Map<String, AsrProvider> providers;

    public AsrProviderRegistry(List<AsrProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toMap(
            item -> item.providerType().toUpperCase(Locale.ROOT),
            Function.identity(),
            (left, right) -> {
                throw new IllegalStateException("重复的 ASR Provider 类型：" + left.providerType());
            }
        ));
    }

    public AsrProvider get(String providerType) {
        if (StringUtils.isBlank(providerType)) {
            throw new ServiceException("ASR Provider 类型不能为空");
        }
        AsrProvider provider = providers.get(providerType.toUpperCase(Locale.ROOT));
        if (provider == null) {
            throw new ServiceException("暂不支持该 ASR Provider 类型：" + providerType);
        }
        return provider;
    }
}
