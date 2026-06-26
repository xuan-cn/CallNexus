package org.dromara.ai.provider;

import org.dromara.ai.domain.AiTtsProvider;

public interface AsrProvider {
    String providerType();
    AsrTranscribeResult transcribe(AiTtsProvider provider, AsrTranscribeRequest request);
}
