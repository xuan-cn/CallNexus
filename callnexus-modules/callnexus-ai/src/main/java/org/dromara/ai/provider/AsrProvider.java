package org.dromara.ai.provider;

import org.dromara.ai.domain.AiSpeechProvider;

public interface AsrProvider {
    String providerType();
    AsrTranscribeResult transcribe(AiSpeechProvider provider, AsrTranscribeRequest request);
}
