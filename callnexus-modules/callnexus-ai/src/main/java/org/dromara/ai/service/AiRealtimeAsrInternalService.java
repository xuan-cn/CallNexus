package org.dromara.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.domain.response.AiRealtimeAsrResponse;
import org.dromara.ai.provider.AsrProviderRegistry;
import org.dromara.ai.provider.AsrSegment;
import org.dromara.ai.provider.AsrTranscribeRequest;
import org.dromara.ai.provider.AsrTranscribeResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AI 实时 ASR 桥接服务，与 {@link AiRealtimeTtsInternalService} 对称：
 * /tts 是“文本进、音频出”，/asr 是“音频进、文本出”。
 * 供 UniMRCP callnexusrecog 插件收齐一句语音后整段 POST 调用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiRealtimeAsrInternalService {

    private static final int DEFAULT_SAMPLE_RATE = 8000;
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    private static final String DEFAULT_LANGUAGE = "zh-CN";
    private static final String BUSINESS_TYPE = "AI_REALTIME_MRCP";

    private final FreeSwitchNodeQueryService nodeQueryService;
    private final AsrProviderRegistry asrProviderRegistry;
    private final AiSpeechProviderSelector speechProviderSelector;

    public AiRealtimeAsrResponse transcribe(String nodeCode, String nodeToken, String tenantId,
                                            String contentType, String language, String callUuid,
                                            Long agentId, Long flowId, byte[] audio) {
        validateRequest(nodeCode, nodeToken, tenantId, audio);
        Long nodeId = nodeQueryService.resolveEnabledNodeIdByAgentToken(tenantId, nodeCode, nodeToken);
        if (nodeId == null) {
            throw new ServiceException("AI 实时 ASR 节点鉴权失败");
        }
        return TenantHelper.dynamic(tenantId,
            () -> transcribeInTenant(nodeId, contentType, language, callUuid, agentId, flowId, audio));
    }

    private AiRealtimeAsrResponse transcribeInTenant(Long nodeId, String contentType, String language,
                                                     String callUuid, Long agentId, Long flowId, byte[] audio) {
        AiSpeechProvider provider = defaultRealtimeAsrProvider();
        AudioFormat audioFormat = parseContentType(contentType);
        String resolvedLanguage = StringUtils.isBlank(language) ? DEFAULT_LANGUAGE : language;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", nodeId);
        metadata.put("language", resolvedLanguage);
        if (StringUtils.isNotBlank(callUuid)) {
            metadata.put("callUuid", callUuid);
        }
        if (agentId != null) {
            metadata.put("agentId", agentId);
        }
        if (flowId != null) {
            metadata.put("flowId", flowId);
        }

        long startNanos = System.nanoTime();
        AsrTranscribeResult result;
        try {
            result = asrProviderRegistry.get(provider.getProviderType()).transcribe(provider,
                new AsrTranscribeRequest(audio, audioFormat.format(), audioFormat.sampleRate(), BUSINESS_TYPE, metadata));
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("AI 实时 ASR 调用失败：" + exception.getMessage());
        }
        long costMs = (System.nanoTime() - startNanos) / 1_000_000L;

        String text = result == null ? null : result.fullText();
        Double confidence = confidenceOf(result);
        log.info("AI 实时 ASR 识别完成，nodeId={}，providerCode={}，format={}，sampleRate={}，audioBytes={}，textLength={}，confidence={}，costMs={}",
            nodeId, provider.getProviderCode(), audioFormat.format(), audioFormat.sampleRate(), audio.length,
            StringUtils.isBlank(text) ? 0 : text.length(), confidence, costMs);
        return new AiRealtimeAsrResponse(StringUtils.isBlank(text) ? "" : text.trim(), confidence, resolvedLanguage);
    }

    private AiSpeechProvider defaultRealtimeAsrProvider() {
        // UniMRCP sends one completed utterance to this endpoint, so select the finite-audio ASR capability.
        return speechProviderSelector.requireDefaultRecordingAsr();
    }

    private void validateRequest(String nodeCode, String nodeToken, String tenantId, byte[] audio) {
        if (StringUtils.isBlank(nodeCode) || StringUtils.isBlank(nodeToken)) {
            throw new ServiceException("AI 实时 ASR 节点鉴权失败");
        }
        if (StringUtils.isBlank(tenantId)) {
            throw new ServiceException("租户ID不能为空");
        }
        if (audio == null || audio.length == 0) {
            throw new ServiceException("ASR 音频不能为空");
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            throw new ServiceException("ASR 音频不能超过 20MB");
        }
    }

    private AudioFormat parseContentType(String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return new AudioFormat("pcm", DEFAULT_SAMPLE_RATE);
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        int sampleRate = parseRate(lower);
        int resolvedSampleRate = sampleRate <= 0 ? DEFAULT_SAMPLE_RATE : sampleRate;
        if (lower.startsWith("audio/wav") || lower.startsWith("audio/x-wav") || lower.startsWith("audio/wave")) {
            return new AudioFormat("wav", resolvedSampleRate);
        }
        // audio/L16、audio/pcm、application/octet-stream 等均按裸 PCM 处理
        return new AudioFormat("pcm", resolvedSampleRate);
    }

    private int parseRate(String lowerContentType) {
        int idx = lowerContentType.indexOf("rate=");
        if (idx < 0) {
            return -1;
        }
        int start = idx + "rate=".length();
        StringBuilder digits = new StringBuilder();
        for (int i = start; i < lowerContentType.length(); i++) {
            char ch = lowerContentType.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private Double confidenceOf(AsrTranscribeResult result) {
        if (result == null || result.segments() == null || result.segments().isEmpty()) {
            return null;
        }
        double max = 0;
        boolean found = false;
        for (AsrSegment segment : result.segments()) {
            if (segment.confidence() != null) {
                found = true;
                max = Math.max(max, segment.confidence().doubleValue());
            }
        }
        return found ? max : null;
    }

    private record AudioFormat(String format, int sampleRate) {
    }
}
