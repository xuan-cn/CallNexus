package org.dromara.ai.provider;

import org.dromara.ai.domain.AiSpeechProvider;

/** 实时 ASR Provider 契约；本阶段仅定义扩展入口，不接入 FreeSWITCH 音频流。 */
public interface StreamingAsrProvider {
    String providerType();

    StreamingAsrSession open(AiSpeechProvider provider, StreamingAsrRequest request, StreamingAsrListener listener);
}

