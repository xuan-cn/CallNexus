package org.dromara.ai.provider;

import cn.hutool.core.lang.Dict;
import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 阿里云智能语音交互 NLS 语音合成适配器。
 * <p>
 * 配置约定：
 * authHeaderName = AccessKey ID
 * authToken = AccessKey Secret
 * remark = {"appKey":"xxx","region":"cn-shanghai"}
 */
@Component
@Slf4j
public class AliyunNlsTtsProvider implements TtsProvider {

    private static final String TYPE = "ALIYUN_NLS";
    private static final String DEFAULT_REGION = "cn-shanghai";
    private static final String DEFAULT_GATEWAY = "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1";
    private static final String DEFAULT_VOICE = "xiaoyun";

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public TtsGenerateResult generate(AiSpeechProvider provider, TtsGenerateRequest request) {
        Dict config = config(provider);
        String appKey = firstText(config, "appKey", "app_key", "app-key");
        String accessKeyId = provider.getAuthHeaderName();
        String accessKeySecret = provider.getAuthToken();
        if (StringUtils.isBlank(appKey)) {
            throw new ServiceException("阿里云 NLS AppKey 不能为空，请在备注 JSON 中配置 appKey");
        }
        if (StringUtils.isBlank(accessKeyId) || StringUtils.isBlank(accessKeySecret)) {
            throw new ServiceException("阿里云 NLS AccessKey ID 和 AccessKey Secret 不能为空");
        }

        String region = StringUtils.blankToDefault(firstText(config, "region"), DEFAULT_REGION);
        String endpoint = endpoint(provider, region);
        String token = createToken(accessKeyId, accessKeySecret, region);
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        String voice = voice(provider, request, config);
        String format = format(request);
        int sampleRate = sampleRate(request);

        log.info("调用阿里云 NLS TTS，providerCode={}，region={}，voice={}，format={}，sampleRate={}，endpoint={}",
            provider.getProviderCode(), region, voice, format, sampleRate, endpoint);

        NlsClient client = new NlsClient(endpoint, token);
        SpeechSynthesizer synthesizer = null;
        try {
            synthesizer = new SpeechSynthesizer(client, listener(audio));
            synthesizer.setAppKey(appKey);
            synthesizer.setText(request.text());
            synthesizer.setVoice(voice);
            synthesizer.setFormat(outputFormat(format));
            synthesizer.setSampleRate(sampleRate(sampleRate));
            synthesizer.start();
            synthesizer.waitForComplete();
        } catch (Exception exception) {
            throw new ServiceException("阿里云 NLS TTS 调用失败：" + exception.getMessage());
        } finally {
            if (synthesizer != null) {
                try {
                    synthesizer.close();
                } catch (Exception exception) {
                    log.debug("关闭阿里云 NLS SpeechSynthesizer 失败，error={}", exception.getMessage());
                }
            }
            client.shutdown();
        }

        byte[] bytes = audio.toByteArray();
        if (bytes.length == 0) {
            throw new ServiceException("阿里云 NLS TTS 未返回音频内容");
        }
        return new TtsGenerateResult(bytes, contentType(format), "." + format, null);
    }

    private SpeechSynthesizerListener listener(ByteArrayOutputStream audio) {
        return new SpeechSynthesizerListener() {
            @Override
            public void onMessage(ByteBuffer message) {
                ByteBuffer buffer = message.slice();
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                audio.write(chunk, 0, chunk.length);
            }

            @Override
            public void onComplete(SpeechSynthesizerResponse response) {
                log.debug("阿里云 NLS TTS 合成完成，requestId={}，status={}", response.getTaskId(), response.getStatus());
            }

            @Override
            public void onFail(SpeechSynthesizerResponse response) {
                throw new ServiceException("阿里云 NLS TTS 合成失败，requestId=" + response.getTaskId()
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

    private Dict config(AiSpeechProvider provider) {
        return StringUtils.isBlank(provider.getRemark()) ? null : JsonUtils.parseObject(provider.getRemark(), Dict.class);
    }

    private String voice(AiSpeechProvider provider, TtsGenerateRequest request, Dict config) {
        if (StringUtils.isNotBlank(request.voice())) {
            return request.voice();
        }
        String remarkVoice = firstText(config, "voice");
        if (StringUtils.isNotBlank(remarkVoice)) {
            return remarkVoice;
        }
        if (StringUtils.isNotBlank(provider.getDefaultVoice())) {
            return provider.getDefaultVoice();
        }
        return DEFAULT_VOICE;
    }

    private String format(TtsGenerateRequest request) {
        String format = StringUtils.isBlank(request.format()) ? "wav" : request.format().replace(".", "");
        format = format.toLowerCase(Locale.ROOT);
        if (!List.of("wav", "mp3", "pcm").contains(format)) {
            throw new ServiceException("阿里云 NLS TTS 暂不支持音频格式：" + format);
        }
        return format;
    }

    private int sampleRate(TtsGenerateRequest request) {
        return request.sampleRate() == null || request.sampleRate() <= 0 ? 8000 : request.sampleRate();
    }

    private OutputFormatEnum outputFormat(String format) {
        return switch (format) {
            case "mp3" -> OutputFormatEnum.MP3;
            case "pcm" -> OutputFormatEnum.PCM;
            default -> OutputFormatEnum.WAV;
        };
    }

    private SampleRateEnum sampleRate(int sampleRate) {
        return switch (sampleRate) {
            case 16000 -> SampleRateEnum.SAMPLE_RATE_16K;
            default -> SampleRateEnum.SAMPLE_RATE_8K;
        };
    }

    private String contentType(String format) {
        return switch (format) {
            case "mp3" -> "audio/mpeg";
            case "pcm" -> "audio/pcm";
            default -> "audio/wav";
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
