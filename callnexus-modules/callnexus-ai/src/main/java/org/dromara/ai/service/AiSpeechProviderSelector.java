package org.dromara.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.mapper.AiSpeechProviderMapper;
import org.dromara.ai.provider.AsrProviderRegistry;
import org.dromara.ai.provider.StreamingAsrProviderRegistry;
import org.dromara.ai.provider.StreamingTtsProviderRegistry;
import org.dromara.ai.provider.TtsProviderRegistry;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiSpeechProviderSelector {
    private final AiSpeechProviderMapper providerMapper;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final AsrProviderRegistry asrProviderRegistry;
    private final StreamingAsrProviderRegistry streamingAsrProviderRegistry;
    private final StreamingTtsProviderRegistry streamingTtsProviderRegistry;

    public AiSpeechProvider requireDefaultTts() {
        AiSpeechProvider provider = defaultProvider(AiSpeechProvider::getTtsEnabled, AiSpeechProvider::getDefaultTts,
            "未配置默认 TTS 语音服务商");
        ttsProviderRegistry.get(provider.getProviderType());
        return provider;
    }

    public AiSpeechProvider requireDefaultStreamingTts() {
        AiSpeechProvider provider = defaultProvider(AiSpeechProvider::getStreamingTtsEnabled,
            AiSpeechProvider::getDefaultStreamingTts, "未配置默认实时 TTS 语音服务商");
        streamingTtsProviderRegistry.get(provider.getProviderType());
        return provider;
    }

    public AiSpeechProvider requireDefaultRecordingAsr() {
        AiSpeechProvider provider = defaultProvider(AiSpeechProvider::getRecordingAsrEnabled,
            AiSpeechProvider::getDefaultRecordingAsr, "未配置默认录音 ASR 语音服务商");
        asrProviderRegistry.get(provider.getProviderType());
        return provider;
    }

    public AiSpeechProvider requireDefaultStreamingAsr() {
        AiSpeechProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiSpeechProvider>()
            .eq(AiSpeechProvider::getEnabled, true)
            .eq(AiSpeechProvider::getStreamingAsrEnabled, true)
            .eq(AiSpeechProvider::getDefaultStreamingAsr, true)
            .last("limit 1"));
        if (provider == null) {
            AiSpeechProvider enabled = providerMapper.selectOne(new LambdaQueryWrapper<AiSpeechProvider>()
                .eq(AiSpeechProvider::getEnabled, true)
                .eq(AiSpeechProvider::getStreamingAsrEnabled, true)
                .last("limit 1"));
            if (enabled != null) {
                throw new ServiceException("流式 ASR 已启用，但服务商“" + enabled.getProviderName() + "”尚未设为默认流式 ASR");
            }
            throw new ServiceException("未配置已启用的流式 ASR 语音服务商");
        }
        streamingAsrProviderRegistry.get(provider.getProviderType());
        return provider;
    }

    private AiSpeechProvider defaultProvider(SFunction<AiSpeechProvider, ?> capability,
                                             SFunction<AiSpeechProvider, ?> defaultFlag,
                                             String message) {
        AiSpeechProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiSpeechProvider>()
            .eq(AiSpeechProvider::getEnabled, true)
            .eq(capability, true)
            .eq(defaultFlag, true)
            .last("limit 1"));
        if (provider == null) {
            throw new ServiceException(message);
        }
        return provider;
    }
}
