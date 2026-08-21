package org.dromara.ai.provider;

import org.dromara.ai.domain.AiSpeechProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AliyunDashScopeRealtimeSpeechProviderTest {

    @Test
    void shouldSplitExplicitFileAndRealtimeModels() {
        AiSpeechProvider provider = provider("{\"fileModel\":\"qwen3-asr-flash\",\"realtimeModel\":\"qwen3-asr-flash-realtime\"}");

        assertEquals("qwen3-asr-flash", AliyunDashScopeRealtimeSpeechProvider.fileAsrModel(provider));
        assertEquals("qwen3-asr-flash-realtime", AliyunDashScopeRealtimeSpeechProvider.realtimeAsrModel(provider));
    }

    @Test
    void shouldNotSendLegacyRealtimeModelToFileAsr() {
        AiSpeechProvider provider = provider("{\"model\":\"qwen3-asr-flash-realtime\"}");

        assertEquals("qwen3-asr-flash", AliyunDashScopeRealtimeSpeechProvider.fileAsrModel(provider));
        assertEquals("qwen3-asr-flash-realtime", AliyunDashScopeRealtimeSpeechProvider.realtimeAsrModel(provider));
    }

    @Test
    void shouldNotUseLegacyFileModelForRealtimeAsr() {
        AiSpeechProvider provider = provider("{\"model\":\"qwen3-asr-flash\"}");

        assertEquals("qwen3-asr-flash", AliyunDashScopeRealtimeSpeechProvider.fileAsrModel(provider));
        assertEquals("qwen3-asr-flash-realtime", AliyunDashScopeRealtimeSpeechProvider.realtimeAsrModel(provider));
    }

    private static AiSpeechProvider provider(String asrOptionsJson) {
        AiSpeechProvider provider = new AiSpeechProvider();
        provider.setAsrOptionsJson(asrOptionsJson);
        return provider;
    }
}
