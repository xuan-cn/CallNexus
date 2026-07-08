package org.dromara.ai.provider;

import org.dromara.ai.domain.AiSpeechProvider;

public interface StreamingTtsProvider {
    String providerType();

    StreamingTtsSession open(AiSpeechProvider provider, StreamingTtsRequest request, StreamingTtsListener listener);
}
