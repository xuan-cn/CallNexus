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
import org.dromara.ai.domain.AiTtsProvider;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 阿里云智能语音交互 NLS 录音转写适配器。
 * <p>
 * 配置复用 ALIYUN_NLS 服务商：
 * authHeaderName = AccessKey ID
 * authToken = AccessKey Secret
 * remark = {"appKey":"xxx","region":"cn-shanghai"}
 */
@Component
@Slf4j
public class AliyunNlsAsrProvider implements AsrProvider {

    private static final String TYPE = "ALIYUN_NLS";
    private static final String DEFAULT_REGION = "cn-shanghai";
    private static final String DEFAULT_GATEWAY = "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1";

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public AsrTranscribeResult transcribe(AiTtsProvider provider, AsrTranscribeRequest request) {
        if (request.audioBytes() == null || request.audioBytes().length == 0) {
            throw new ServiceException("ASR 输入音频不能为空");
        }
        Dict config = config(provider);
        String appKey = firstText(config, "appKey", "app_key", "app-key");
        if (StringUtils.isBlank(appKey)) {
            throw new ServiceException("阿里云 NLS AppKey 不能为空，请在备注 JSON 中配置 appKey");
        }
        if (StringUtils.isBlank(provider.getAuthHeaderName()) || StringUtils.isBlank(provider.getAuthToken())) {
            throw new ServiceException("阿里云 NLS AccessKey ID 和 AccessKey Secret 不能为空");
        }

        String region = StringUtils.blankToDefault(firstText(config, "region"), DEFAULT_REGION);
        String endpoint = endpoint(provider, region);
        String token = createToken(provider.getAuthHeaderName(), provider.getAuthToken(), region);
        String format = format(request);
        int sampleRate = sampleRate(request);
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
            transcriber.setEnableIntermediateResult(false);
            transcriber.setEnablePunctuation(true);
            transcriber.setEnableITN(true);
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

    private String endpoint(AiTtsProvider provider, String region) {
        if (StringUtils.isNotBlank(provider.getEndpointUrl())) {
            return provider.getEndpointUrl().trim();
        }
        return switch (region) {
            case "cn-beijing" -> "wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1";
            case "cn-shenzhen" -> "wss://nls-gateway-cn-shenzhen.aliyuncs.com/ws/v1";
            case "cn-shanghai" -> DEFAULT_GATEWAY;
            default -> DEFAULT_GATEWAY;
        };
    }

    private Dict config(AiTtsProvider provider) {
        return StringUtils.isBlank(provider.getRemark()) ? null : JsonUtils.parseObject(provider.getRemark(), Dict.class);
    }

    private String format(AsrTranscribeRequest request) {
        String format = StringUtils.isBlank(request.format()) ? "wav" : request.format().replace(".", "");
        format = format.toLowerCase(Locale.ROOT);
        if (!List.of("wav", "pcm", "opus", "opu", "speex").contains(format)) {
            throw new ServiceException("阿里云 NLS ASR 暂不支持音频格式：" + format + "，请使用 WAV/PCM/OPUS/SPEEX");
        }
        return format;
    }

    private int sampleRate(AsrTranscribeRequest request) {
        return request.sampleRate() == null || request.sampleRate() <= 0 ? 8000 : request.sampleRate();
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
