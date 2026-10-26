package org.dromara.ai.provider;

import cn.hutool.core.lang.Dict;
import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizerResponse;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 阿里云智能语音交互 NLS 流式文本语音合成适配器。 */
@Component
@Slf4j
public class AliyunNlsStreamingTtsProvider implements StreamingTtsProvider {

    private static final String TYPE = "ALIYUN_NLS";
    private static final String DEFAULT_STREAMING_ENDPOINT = "wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1";
    private static final String DEFAULT_VOICE = "xiaoyun";

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public StreamingTtsSession open(AiSpeechProvider provider, StreamingTtsRequest request,
                                    StreamingTtsListener listener) {
        Dict config = config(provider);
        String appKey = firstText(config, "appKey", "app_key", "app-key");
        String accessKeyId = StringUtils.blankToDefault(
            firstText(config, "accessKeyId", "access_key_id", "access-key-id"),
            provider.getAuthHeaderName());
        String accessKeySecret = provider.getAuthToken();
        if (StringUtils.isBlank(appKey)) {
            throw new ServiceException("阿里云 NLS AppKey 不能为空");
        }
        if (StringUtils.isBlank(accessKeyId) || StringUtils.isBlank(accessKeySecret)) {
            throw new ServiceException("阿里云 NLS AccessKey ID 和 AccessKey Secret 不能为空");
        }

        String region = StringUtils.blankToDefault(firstText(config, "region"), "cn-shanghai");
        String endpoint = streamingEndpoint(provider);
        String token = createToken(accessKeyId, accessKeySecret, region);
        NlsClient client = new NlsClient(endpoint, token);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean firstAudioReceived = new AtomicBoolean();

        try {
            FlowingSpeechSynthesizer synthesizer = new FlowingSpeechSynthesizer(client,
                streamingListener(provider, listener, firstAudioReceived, completed));
            synthesizer.setAppKey(appKey);
            synthesizer.setVoice(voice(provider, request));
            synthesizer.setFormat(OutputFormatEnum.PCM);
            synthesizer.setSampleRate(sampleRate(request));
            applyOptions(synthesizer, provider.getStreamingTtsOptionsJson());
            synthesizer.start();
            log.info("已打开阿里云 NLS 实时 TTS，providerCode={}，voice={}，sampleRate={}，endpoint={}",
                provider.getProviderCode(), voice(provider, request), sampleRateValue(request), endpoint);

            return new StreamingTtsSession() {
                private final AtomicBoolean finishing = new AtomicBoolean();

                @Override
                public void append(String text) {
                    if (!closed.get() && StringUtils.isNotBlank(text)) {
                        synthesizer.send(text);
                    }
                }

                @Override
                public void commit() {
                    // NLS FlowingSpeechSynthesizer 的 send 即提交当前文本片段。
                }

                @Override
                public void finish() {
                    if (!closed.get() && finishing.compareAndSet(false, true)) {
                        try {
                            synthesizer.stop();
                        } catch (Exception exception) {
                            listener.onError("停止阿里云 NLS 实时 TTS 失败：" + rootMessage(exception));
                        }
                    }
                }

                @Override
                public void close() {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        synthesizer.close();
                    } catch (Exception exception) {
                        log.debug("关闭阿里云 NLS 实时 TTS 合成器失败，error={}", exception.getMessage());
                    } finally {
                        client.shutdown();
                    }
                }
            };
        } catch (Exception exception) {
            client.shutdown();
            throw new ServiceException("打开阿里云 NLS 实时 TTS 失败：" + rootMessage(exception));
        }
    }

    private FlowingSpeechSynthesizerListener streamingListener(AiSpeechProvider provider,
                                                               StreamingTtsListener listener,
                                                               AtomicBoolean firstAudioReceived,
                                                               AtomicBoolean completed) {
        return new FlowingSpeechSynthesizerListener() {
            @Override
            public void onSynthesisStart(FlowingSpeechSynthesizerResponse response) {
                log.info("阿里云 NLS 实时 TTS 已开始，providerCode={}，taskId={}",
                    provider.getProviderCode(), response.getTaskId());
                listener.onStarted();
            }

            @Override
            public void onSentenceBegin(FlowingSpeechSynthesizerResponse response) {
                log.debug("阿里云 NLS 实时 TTS 句子开始，taskId={}", response.getTaskId());
            }

            @Override
            public void onAudioData(ByteBuffer message) {
                ByteBuffer buffer = message.slice();
                if (!buffer.hasRemaining()) {
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                if (firstAudioReceived.compareAndSet(false, true)) {
                    log.info("阿里云 NLS 实时 TTS 已收到首包音频，providerCode={}，bytes={}",
                        provider.getProviderCode(), chunk.length);
                }
                listener.onAudio(chunk);
            }

            @Override
            public void onSentenceEnd(FlowingSpeechSynthesizerResponse response) {
                log.debug("阿里云 NLS 实时 TTS 句子结束，taskId={}", response.getTaskId());
            }

            @Override
            public void onSynthesisComplete(FlowingSpeechSynthesizerResponse response) {
                log.info("阿里云 NLS 实时 TTS 合成完成，providerCode={}，taskId={}，receivedAudio={}",
                    provider.getProviderCode(), response.getTaskId(), firstAudioReceived.get());
                if (completed.compareAndSet(false, true)) {
                    listener.onCompleted();
                }
            }

            @Override
            public void onFail(FlowingSpeechSynthesizerResponse response) {
                if (completed.compareAndSet(false, true)) {
                    listener.onError("阿里云 NLS 实时 TTS 失败，taskId=" + response.getTaskId()
                        + "，status=" + response.getStatus() + "，message=" + response.getStatusText());
                }
            }

            @Override
            public void onSentenceSynthesis(FlowingSpeechSynthesizerResponse response) {
                log.trace("阿里云 NLS 实时 TTS 句子合成中，taskId={}", response.getTaskId());
            }
        };
    }

    private String createToken(String accessKeyId, String accessKeySecret, String region) {
        try {
            AccessToken accessToken = new AccessToken(accessKeyId, accessKeySecret);
            accessToken.apply();
            if (StringUtils.isBlank(accessToken.getToken())) {
                throw new ServiceException("获取阿里云 NLS Token 失败，请检查 AccessKey ID、AccessKey Secret 及 RAM 权限");
            }
            if (accessToken.getExpireTime() > 0 && accessToken.getExpireTime() < Instant.now().getEpochSecond()) {
                throw new ServiceException("阿里云 NLS Token 已过期");
            }
            return accessToken.getToken();
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("获取阿里云 NLS Token 失败，region=" + region + "，error=" + rootMessage(exception));
        }
    }

    private void applyOptions(FlowingSpeechSynthesizer synthesizer, String optionsJson) {
        if (StringUtils.isBlank(optionsJson)) {
            return;
        }
        Dict options = JsonUtils.parseObject(optionsJson, Dict.class);
        if (options == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            synthesizer.addCustomedParam(entry.getKey(), entry.getValue());
        }
    }

    static String streamingEndpoint(AiSpeechProvider provider) {
        return StringUtils.isBlank(provider.getStreamingTtsEndpointUrl())
            ? DEFAULT_STREAMING_ENDPOINT : provider.getStreamingTtsEndpointUrl().trim();
    }

    private Dict config(AiSpeechProvider provider) {
        return StringUtils.isBlank(provider.getCredentialJson())
            ? null : JsonUtils.parseObject(provider.getCredentialJson(), Dict.class);
    }

    private String voice(AiSpeechProvider provider, StreamingTtsRequest request) {
        if (StringUtils.isNotBlank(request.voice()) && !"default".equalsIgnoreCase(request.voice().trim())) {
            return request.voice().trim();
        }
        return StringUtils.blankToDefault(provider.getStreamingTtsVoice(),
            StringUtils.blankToDefault(provider.getDefaultVoice(), DEFAULT_VOICE));
    }

    private SampleRateEnum sampleRate(StreamingTtsRequest request) {
        return sampleRateValue(request) == 16000 ? SampleRateEnum.SAMPLE_RATE_16K : SampleRateEnum.SAMPLE_RATE_8K;
    }

    static int sampleRateValue(StreamingTtsRequest request) {
        return request.sampleRate() != null && request.sampleRate() == 16000 ? 16000 : 8000;
    }

    private static String firstText(Dict dict, String... keys) {
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

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.isBlank(current.getMessage()) ? current.getClass().getSimpleName() : current.getMessage();
    }
}
