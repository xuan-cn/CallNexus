package org.dromara.ai.provider;

import cn.hutool.core.lang.Dict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** FunASR HTTP sentence-level recognition adapter. */
@Component
@RequiredArgsConstructor
@Slf4j
public class FunAsrProvider implements AsrProvider {

    private static final String TYPE = "FUNASR";
    private static final int DEFAULT_SAMPLE_RATE = 16000;

    private final Pcm16AudioNormalizer audioNormalizer;
    private final FunAsrHttpClient httpClient;

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public AsrTranscribeResult transcribe(AiSpeechProvider provider, AsrTranscribeRequest request) {
        if (request.audioBytes() == null || request.audioBytes().length == 0) {
            throw new ServiceException("FunASR 输入音频不能为空");
        }
        Map<String, Object> options = options(provider.getAsrOptionsJson());
        int targetSampleRate = positiveInt(options.get("audioFs"),
            provider.getAsrSampleRate() == null ? DEFAULT_SAMPLE_RATE : provider.getAsrSampleRate());
        Pcm16AudioNormalizer.NormalizedAudio audio = audioNormalizer.normalize(
            request.audioBytes(), request.format(), request.sampleRate(), targetSampleRate);
        String wavName = metadataText(request.metadata(), "callUuid");
        if (StringUtils.isBlank(wavName)) {
            wavName = "callnexus-" + UUID.randomUUID().toString().replace("-", "");
        }
        String model = StringUtils.blankToDefault(provider.getRecordingAsrModel(), "sensevoice");
        Map<String, Object> parameters = mapOption(options.get("parameters"));

        long start = System.nanoTime();
        AsrTranscribeResult result = httpClient.transcribe(provider, audioNormalizer.toWave(audio),
            safeFileName(wavName) + ".wav", model, parameters);
        log.info("FunASR 识别完成，providerCode={}，inputFormat={}，inputSampleRate={}，targetSampleRate={}，audioBytes={}，textLength={}，costMs={}",
            provider.getProviderCode(), request.format(), request.sampleRate(), audio.sampleRate(), audio.bytes().length,
            result == null || result.fullText() == null ? 0 : result.fullText().length(),
            (System.nanoTime() - start) / 1_000_000L);
        return result;
    }

    private Map<String, Object> mapOption(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key != null && item != null) {
                    result.put(String.valueOf(key), item);
                }
            });
        }
        return result;
    }

    private String safeFileName(String value) {
        String fileName = value.replaceAll("[^a-zA-Z0-9_-]", "_");
        return StringUtils.blankToDefault(fileName, "callnexus");
    }

    private Map<String, Object> options(String optionsJson) {
        if (StringUtils.isBlank(optionsJson)) {
            return new LinkedHashMap<>();
        }
        Dict parsed;
        try {
            parsed = JsonUtils.parseObject(optionsJson, Dict.class);
        } catch (Exception exception) {
            throw new ServiceException("FunASR 扩展参数不是有效 JSON：" + exception.getMessage());
        }
        return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
    }

    private int positiveInt(Object value, int defaultValue) {
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (value != null) {
            try {
                int parsed = Integer.parseInt(String.valueOf(value));
                return parsed > 0 ? parsed : defaultValue;
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        return String.valueOf(metadata.get(key));
    }
}
