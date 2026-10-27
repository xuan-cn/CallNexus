package org.dromara.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.speech.definition.SpeechCapability;
import org.dromara.ai.speech.definition.SpeechProviderDefinitionRegistry;
import org.dromara.common.core.utils.SpringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunDashScopeRealtimeSpeechProviderTest {

    private static AnnotationConfigApplicationContext applicationContext;

    @BeforeAll
    static void initializeJsonUtils() {
        applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        applicationContext.refresh();
        new SpringUtils().setApplicationContext(applicationContext);
    }

    @AfterAll
    static void closeApplicationContext() {
        applicationContext.close();
    }

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

    @Test
    void shouldPrepareQwenAudioRequestForTelephonePcm() {
        byte[] pcm = new byte[]{1, 2, 3, 4};
        AsrTranscribeRequest request = new AsrTranscribeRequest(pcm, "pcm", 8000, "TEST", Map.of());

        AliyunDashScopeRealtimeSpeechProvider.PreparedAudio audio =
            AliyunDashScopeRealtimeSpeechProvider.prepareQwenAudio(request);

        assertEquals("wav", audio.format());
        assertEquals(8000, audio.sampleRate());
        assertEquals(48, audio.bytes().length);
        assertEquals("RIFF", new String(audio.bytes(), 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildQwenAudioGenerationPayload() {
        AiSpeechProvider provider = provider("{\"keep_dialect\":true,\"language_hints\":[\"zh\"]}");
        provider.setRecordingAsrModel("qwen-audio-3.1-asr-flash");
        AsrTranscribeRequest request = new AsrTranscribeRequest(new byte[]{1, 2}, "wav", 8000, "TEST", Map.of());
        AliyunDashScopeRealtimeSpeechProvider.PreparedAudio audio =
            AliyunDashScopeRealtimeSpeechProvider.prepareQwenAudio(request);

        Map<String, Object> payload = AliyunDashScopeRealtimeSpeechProvider.buildQwenAudioAsrPayload(
            provider, request, audio);

        assertEquals("qwen-audio-3.1-asr-flash", payload.get("model"));
        Map<String, Object> parameters = (Map<String, Object>) payload.get("parameters");
        assertEquals("wav", parameters.get("format"));
        assertEquals("8000", parameters.get("sample_rate"));
        assertEquals(true, parameters.get("keep_dialect"));
        assertEquals(List.of("zh"), parameters.get("language_hints"));
    }

    @Test
    void shouldResolveQwenAudioEndpointAndResponse() {
        AiSpeechProvider provider = provider(null);
        provider.setCredentialJson("{\"workspaceId\":\"ws-test\"}");

        assertEquals(
            "https://ws-test.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            AliyunDashScopeRealtimeSpeechProvider.qwenAudioAsrEndpoint(provider));

        AsrTranscribeResult result = AliyunDashScopeRealtimeSpeechProvider.parseQwenAudioAsrResponse(
            "{\"output\":{\"text\":\"识别结果\",\"sentence\":{\"sentence_id\":1,\"begin_time\":20,\"end_time\":300}},\"request_id\":\"r1\"}");
        assertEquals("识别结果", result.fullText());
        assertEquals(1, result.segments().size());
        assertTrue(result.segments().get(0).finalResult());
    }

    @Test
    void shouldExposeQwenAudioModelInBailianRecordingAsrDropdown() {
        var recordingAsr = new SpeechProviderDefinitionRegistry().get("ALIYUN_DASHSCOPE")
            .capabilities().get(SpeechCapability.RECORDING_ASR);

        assertTrue(recordingAsr.models().stream()
            .anyMatch(model -> "qwen-audio-3.1-asr-flash".equals(model.id())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildMessageProtocolWithoutRealtimeSessionEvents() {
        AiSpeechProvider provider = provider("{\"keep_dialect\":true}");
        provider.setStreamingAsrModel("qwen-audio-3.1-asr-flash-message");
        provider.setCredentialJson("{\"workspaceId\":\"ws-test\"}");
        var request = new StreamingAsrRequest("pcm", 16000, null, Map.of());
        Map<String, Object> run = AliyunDashScopeRealtimeSpeechProvider.buildQwenMessageRunTask(provider, request, "task-1");
        assertEquals("run-task", ((Map<String, Object>) run.get("header")).get("action"));
        var payload = (Map<String, Object>) run.get("payload");
        assertEquals("qwen-audio-3.1-asr-flash-message", payload.get("model"));
        var parameters = (Map<String, Object>) payload.get("parameters");
        assertEquals(16000, parameters.get("sample_rate"));
        assertEquals(true, parameters.get("keep_dialect"));
        assertEquals("wss://ws-test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
            AliyunDashScopeRealtimeSpeechProvider.qwenMessageAsrEndpoint(provider));
    }

    @Test
    void shouldParseMessageFinalResultAndIgnoreHeartbeat() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var segment = AliyunDashScopeRealtimeSpeechProvider.parseQwenMessageSegment(mapper.readTree(
            "{\"payload\":{\"output\":{\"sentence\":{\"sentence_id\":2,\"begin_time\":100,\"end_time\":900,\"text\":\"hello\",\"sentence_end\":true}}}}"));
        assertEquals("hello", segment.text());
        assertTrue(segment.finalResult());
        assertEquals(null, AliyunDashScopeRealtimeSpeechProvider.parseQwenMessageSegment(mapper.readTree(
            "{\"payload\":{\"output\":{\"sentence\":{\"heartbeat\":true,\"text\":\"ignored\"}}}}")));
    }

    private static AiSpeechProvider provider(String asrOptionsJson) {
        AiSpeechProvider provider = new AiSpeechProvider();
        provider.setAsrOptionsJson(asrOptionsJson);
        return provider;
    }
}
