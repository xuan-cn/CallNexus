package org.dromara.ai.provider;

import org.dromara.ai.domain.AiSpeechProvider;

import java.util.List;

/** Optional capability for providers that can enumerate available voices. */
public interface TtsVoiceCatalogProvider {
    List<String> voices(AiSpeechProvider provider);
}
