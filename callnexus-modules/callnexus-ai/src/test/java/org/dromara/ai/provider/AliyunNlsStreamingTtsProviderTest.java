package org.dromara.ai.provider;

import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.speech.definition.SpeechCapability;
import org.dromara.ai.speech.definition.SpeechProviderDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunNlsStreamingTtsProviderTest {

    @Test
    void shouldExposeNlsStreamingTtsCapability() {
        var definition = new SpeechProviderDefinitionRegistry().get("ALIYUN_NLS");

        assertTrue(definition.capabilities().get(SpeechCapability.STREAMING_TTS).supported());
        assertEquals("wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1",
            definition.resolveEndpoint(SpeechCapability.STREAMING_TTS, Map.of("region", "cn-shanghai")));
    }

    @Test
    void shouldUseConfiguredStreamingEndpoint() {
        AiSpeechProvider provider = new AiSpeechProvider();
        provider.setStreamingTtsEndpointUrl("wss://example.test/ws/v1");

        assertEquals("wss://example.test/ws/v1", AliyunNlsStreamingTtsProvider.streamingEndpoint(provider));
    }

    @Test
    void shouldNormalizeTelephoneSampleRate() {
        assertEquals(8000, AliyunNlsStreamingTtsProvider.sampleRateValue(
            new StreamingTtsRequest("xiaoyun", "pcm", 8000, Map.of())));
        assertEquals(16000, AliyunNlsStreamingTtsProvider.sampleRateValue(
            new StreamingTtsRequest("xiaoyun", "pcm", 16000, Map.of())));
        assertEquals(8000, AliyunNlsStreamingTtsProvider.sampleRateValue(
            new StreamingTtsRequest("xiaoyun", "pcm", 24000, Map.of())));
    }
}
