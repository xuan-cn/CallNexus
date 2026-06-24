package org.dromara.ai.provider;

import org.dromara.ai.domain.AiTtsProvider;

public interface TtsProvider {
    String providerType();

    TtsGenerateResult generate(AiTtsProvider provider, TtsGenerateRequest request);
}
