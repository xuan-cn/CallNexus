package org.dromara.ai.provider;

import org.dromara.ai.domain.AiSpeechProvider;

public interface TtsProvider {
    String providerType();

    TtsGenerateResult generate(AiSpeechProvider provider, TtsGenerateRequest request);
}
