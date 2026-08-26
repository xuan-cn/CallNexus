package org.dromara.ai.provider;

import cn.hutool.core.lang.Dict;
import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** 阿里云智能语音交互 NLS 录音文件识别适配器。 */
@Component
@Slf4j
public class AliyunNlsAsrProvider implements AsrProvider, StreamingAsrProvider {

    private static final String TYPE = "ALIYUN_NLS";
    private static final String DEFAULT_REGION = "cn-shanghai";
    private static final String DEFAULT_GATEWAY = "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1";

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public AsrTranscribeResult transcribe(AiSpeechProvider provider, AsrTranscribeRequest request) {
        if (request.audioBytes() == null || request.audioBytes().length == 0) {
            throw new ServiceException("ASR 输入音频不能为空");
        }
        Dict commonConfig = commonConfig(provider);
        String appKey = firstText(commonConfig, "appKey", "app_key", "app-key");
        if (StringUtils.isBlank(appKey)) {
            throw new ServiceException("阿里云 NLS AppKey 不能为空，请在备注 JSON 中配置 appKey");
        }
        if (StringUtils.isBlank(provider.getAuthHeaderName()) || StringUtils.isBlank(provider.getAuthToken())) {
            throw new ServiceException("阿里云 NLS AccessKey ID 和 AccessKey Secret 不能为空");
        }

        String region = StringUtils.blankToDefault(firstText(commonConfig, "region"), DEFAULT_REGION);
        String endpoint = endpoint(provider, region);
        String token = createToken(provider.getAuthHeaderName(), provider.getAuthToken(), region);
        String format = format(provider, request);
        int sampleRate = sampleRate(provider, request);
        List<AsrSegment> segments = new CopyOnWriteArrayList<>();
        AtomicReference<String> failure = new AtomicReference<>();

        log.info("调用阿里云 NLS ASR，providerCode={}，region={}，format={}，sampleRate={}，endpoint={}，audioBytes={}",
            provider.getProviderCode(), region, format, sampleRate, endpoint, request.audioBytes().length);

        NlsClient client = new NlsClient(endpoint, token);
        SpeechTranscriber transcriber = null;
        try {
            transcriber = new SpeechTranscriber(client, listener(segments, failure));
            transcriber.setAppKey(appKey);
            transcriber.setFormat(inputFormat(format));
            transcriber.setSampleRate(sampleRate(sampleRate));
            transcriber.setEnableIntermediateResult(Boolean.TRUE.equals(provider.getAsrEnableIntermediateResult()));
            transcriber.setEnablePunctuation(!Boolean.FALSE.equals(provider.getAsrEnablePunctuation()));
            transcriber.setEnableITN(!Boolean.FALSE.equals(provider.getAsrEnableItn()));
            if (provider.getAsrSilenceTimeoutMs() != null && provider.getAsrSilenceTimeoutMs() > 0) {
                transcriber.addCustomedParam("max_sentence_silence", provider.getAsrSilenceTimeoutMs());
            }
            if (provider.getAsrMaxSentenceMs() != null && provider.getAsrMaxSentenceMs() > 0) {
                transcriber.addCustomedParam("max_single_segment_time", provider.getAsrMaxSentenceMs());
            }
            applyOptions(transcriber, provider.getAsrOptionsJson());
            transcriber.start();
            transcriber.send(new ByteArrayInputStream(request.audioBytes()));
            transcriber.stop();
        } catch (Exception exception) {
            throw new ServiceException("阿里云 NLS ASR 调用失败：" + exception.getMessage());
        } finally {
            if (transcriber != null) {
                try {
                    transcriber.close();
                } catch (Exception exception) {
                    log.debug("关闭阿里云 NLS SpeechTranscriber 失败，error={}", exception.getMessage());
                }
            }
            client.shutdown();
        }
        if (StringUtils.isNotBlank(failure.get())) {
            throw new ServiceException(failure.get());
        }
        List<AsrSegment> ordered = new ArrayList<>(segments);
        ordered.sort((left, right) -> Integer.compare(
            left.sentenceIndex() == null ? 0 : left.sentenceIndex(),
            right.sentenceIndex() == null ? 0 : right.sentenceIndex()
        ));
        String fullText = String.join("\n", ordered.stream().map(AsrSegment::text).filter(StringUtils::isNotBlank).toList());
        return new AsrTranscribeResult(fullText, ordered);
    }

    @Override
    public StreamingAsrSession open(AiSpeechProvider provider, StreamingAsrRequest request, StreamingAsrListener listener) {
        Dict commonConfig = commonConfig(provider);
        String appKey = firstText(commonConfig, "appKey", "app_key", "app-key");
        if (StringUtils.isBlank(appKey)) {
            throw new ServiceException("阿里云 NLS AppKey 不能为空，请在备注 JSON 中配置 appKey");
        }
        if (StringUtils.isBlank(provider.getAuthHeaderName()) || StringUtils.isBlank(provider.getAuthToken())) {
            throw new ServiceException("阿里云 NLS AccessKey ID 和 AccessKey Secret 不能为空");
        }
        String region = StringUtils.blankToDefault(firstText(commonConfig, "region"), DEFAULT_REGION);
        String endpoint = StringUtils.isNotBlank(provider.getStreamingAsrEndpointUrl())
            ? provider.getStreamingAsrEndpointUrl().trim() : endpoint(provider, region);
        String token = createToken(provider.getAuthHeaderName(), provider.getAuthToken(), region);
        int rate = request.sampleRate() == null || request.sampleRate() <= 0 ? 16000 : request.sampleRate();
        List<AsrSegment> segments = new CopyOnWriteArrayList<>();
        NlsClient client = new NlsClient(endpoint, token);
        try {
            SpeechTranscriber transcriber = new SpeechTranscriber(client, streamingListener(segments, listener));
            transcriber.setAppKey(appKey);
            transcriber.setFormat(InputFormatEnum.PCM);
            transcriber.setSampleRate(sampleRate(rate));
            transcriber.setEnableIntermediateResult(!Boolean.FALSE.equals(provider.getAsrEnableIntermediateResult()));
            transcriber.setEnablePunctuation(!Boolean.FALSE.equals(provider.getAsrEnablePunctuation()));
            transcriber.setEnableITN(!Boolean.FALSE.equals(provider.getAsrEnableItn()));
            if (provider.getAsrSilenceTimeoutMs() != null && provider.getAsrSilenceTimeoutMs() > 0) {
                transcriber.addCustomedParam("max_sentence_silence", provider.getAsrSilenceTimeoutMs());
            }
            if (provider.getAsrMaxSentenceMs() != null && provider.getAsrMaxSentenceMs() > 0) {
                transcriber.addCustomedParam("max_single_segment_time", provider.getAsrMaxSentenceMs());
            }
            applyOptions(transcriber, provider.getAsrOptionsJson());
            transcriber.start();
            log.info("已打开阿里云 NLS 流式 ASR，会话采样率={}，providerCode={}，endpoint={}",
                rate, provider.getProviderCode(), endpoint);
            return new StreamingAsrSession() {
                private volatile boolean closed;

                @Override
                public void send(byte[] audioBytes) {
                    if (!closed && audioBytes != null && audioBytes.length > 0) {
                        transcriber.send(audioBytes);
                    }
                }

                @Override
                public void finish() {
                    if (closed) {
                        return;
                    }
                    try {
                        transcriber.stop();
                    } catch (Exception exception) {
                        listener.onError("停止流式 ASR 失败：" + exception.getMessage());
                    }
                }

                @Override
                public void close() {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    try {
                        transcriber.close();
                    } finally {
                        client.shutdown();
                    }
                }
            };
        } catch (Exception exception) {
            client.shutdown();
            throw new ServiceException("打开阿里云 NLS 流式 ASR 失败：" + exception.getMessage());
        }
    }

    private SpeechTranscriberListener streamingListener(List<AsrSegment> segments, StreamingAsrListener listener) {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                log.debug("阿里云 NLS 流式 ASR 已启动，taskId={}", response.getTaskId());
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
                log.debug("阿里云 NLS 流式 ASR 句子开始，taskId={}，index={}", response.getTaskId(), response.getTransSentenceIndex());
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = StringUtils.blankToDefault(response.getTransSentenceFixedText(), response.getTransSentenceText());
                if (StringUtils.isBlank(text)) {
                    return;
                }
                AsrSegment segment = segment(response, text, true);
                segments.add(segment);
                listener.onResult(segment);
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                if (StringUtils.isNotBlank(text)) {
                    listener.onResult(segment(response, text, false));
                }
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                List<AsrSegment> ordered = new ArrayList<>(segments);
                ordered.sort((left, right) -> Integer.compare(
                    left.sentenceIndex() == null ? 0 : left.sentenceIndex(),
                    right.sentenceIndex() == null ? 0 : right.sentenceIndex()));
                String fullText = String.join("\n", ordered.stream().map(AsrSegment::text)
                    .filter(StringUtils::isNotBlank).toList());
                listener.onCompleted(new AsrTranscribeResult(fullText, ordered));
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                listener.onError("阿里云 NLS 流式 ASR 失败，requestId=" + response.getTaskId()
                    + "，status=" + response.getStatus() + "，message=" + response.getStatusText());
            }
        };
    }

    private AsrSegment segment(SpeechTranscriberResponse response, String text, boolean finalResult) {
        return new AsrSegment(
            response.getTransSentenceIndex(),
            response.getSentenceBeginTime(),
            response.getTransSentenceTime(),
            text,
            response.getConfidence() == null ? null : BigDecimal.valueOf(response.getConfidence()),
            finalResult
        );
    }

    private SpeechTranscriberListener listener(List<AsrSegment> segments, AtomicReference<String> failure) {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                log.debug("阿里云 NLS ASR 转写开始，taskId={}", response.getTaskId());
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
                log.debug("阿里云 NLS ASR 句子开始，taskId={}，index={}", response.getTaskId(), response.getTransSentenceIndex());
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = StringUtils.blankToDefault(response.getTransSentenceFixedText(), response.getTransSentenceText());
                if (StringUtils.isBlank(text)) {
                    return;
                }
                segments.add(new AsrSegment(
                    response.getTransSentenceIndex(),
                    response.getSentenceBeginTime(),
                    response.getTransSentenceTime(),
                    text,
                    response.getConfidence() == null ? null : BigDecimal.valueOf(response.getConfidence()),
                    true
                ));
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                log.debug("阿里云 NLS ASR 中间结果，taskId={}，text={}", response.getTaskId(), response.getTransSentenceText());
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                log.debug("阿里云 NLS ASR 转写完成，taskId={}，status={}", response.getTaskId(), response.getStatus());
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                failure.set("阿里云 NLS ASR 转写失败，requestId=" + response.getTaskId()
                    + "，status=" + response.getStatus() + "，message=" + response.getStatusText());
            }
        };
    }

    private String createToken(String accessKeyId, String accessKeySecret, String region) {
        try {
            AccessToken accessToken = new AccessToken(accessKeyId, accessKeySecret);
            accessToken.apply();
            if (accessToken.getExpireTime() > 0 && accessToken.getExpireTime() < Instant.now().getEpochSecond()) {
                throw new ServiceException("阿里云 NLS Token 已过期");
            }
            return accessToken.getToken();
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("获取阿里云 NLS Token 失败，region=" + region + "，error=" + exception.getMessage());
        }
    }

    private String endpoint(AiSpeechProvider provider, String region) {
        if (StringUtils.isNotBlank(provider.getRecordingAsrEndpointUrl())) {
            return provider.getRecordingAsrEndpointUrl().trim();
        }
        return switch (region) {
            case "cn-beijing" -> "wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1";
            case "cn-shenzhen" -> "wss://nls-gateway-cn-shenzhen.aliyuncs.com/ws/v1";
            default -> DEFAULT_GATEWAY;
        };
    }

    private Dict commonConfig(AiSpeechProvider provider) {
        return StringUtils.isBlank(provider.getCredentialJson()) ? null : JsonUtils.parseObject(provider.getCredentialJson(), Dict.class);
    }

    private void applyOptions(SpeechTranscriber transcriber, String optionsJson) {
        if (StringUtils.isBlank(optionsJson)) {
            return;
        }
        Dict options = JsonUtils.parseObject(optionsJson, Dict.class);
        if (options == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            transcriber.addCustomedParam(entry.getKey(), entry.getValue());
        }
    }

    private String format(AiSpeechProvider provider, AsrTranscribeRequest request) {
        String configured = StringUtils.blankToDefault(request.format(), provider.getAsrFormat());
        String format = StringUtils.blankToDefault(configured, "wav").replace(".", "").toLowerCase(Locale.ROOT);
        if (!List.of("wav", "pcm", "opus", "opu", "speex").contains(format)) {
            throw new ServiceException("阿里云 NLS ASR 暂不支持音频格式：" + format + "，请使用 WAV/PCM/OPUS/SPEEX");
        }
        return format;
    }

    private int sampleRate(AiSpeechProvider provider, AsrTranscribeRequest request) {
        Integer configured = request.sampleRate() == null || request.sampleRate() <= 0 ? provider.getAsrSampleRate() : request.sampleRate();
        return configured == null || configured <= 0 ? 8000 : configured;
    }

    private InputFormatEnum inputFormat(String format) {
        return switch (format) {
            case "pcm" -> InputFormatEnum.PCM;
            case "opus" -> InputFormatEnum.OPUS;
            case "opu" -> InputFormatEnum.OPU;
            case "speex" -> InputFormatEnum.SPEEX;
            default -> InputFormatEnum.WAV;
        };
    }

    private SampleRateEnum sampleRate(int sampleRate) {
        return switch (sampleRate) {
            case 16000 -> SampleRateEnum.SAMPLE_RATE_16K;
            case 24000 -> SampleRateEnum.SAMPLE_RATE_24K;
            case 48000 -> SampleRateEnum.SAMPLE_RATE_48K;
            default -> SampleRateEnum.SAMPLE_RATE_8K;
        };
    }

    private String firstText(Dict dict, String... keys) {
        if (dict == null) {
            return null;
        }
        for (String key : keys) {
            Object value = dict.get(key);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
