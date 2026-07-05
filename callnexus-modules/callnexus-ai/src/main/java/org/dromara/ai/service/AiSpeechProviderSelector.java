package org.dromara.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.mapper.AiSpeechProviderMapper;
import org.dromara.ai.provider.AsrProviderRegistry;
import org.dromara.ai.provider.TtsProviderRegistry;
import org.dromara.ai.provider.StreamingAsrProviderRegistry;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiSpeechProviderSelector {
    private final AiSpeechProviderMapper providerMapper;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final AsrProviderRegistry asrProviderRegistry;
    private final StreamingAsrProviderRegistry streamingAsrProviderRegistry;

    public AiSpeechProvider requireDefaultTts() {
        AiSpeechProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiSpeechProvider>()
            .eq(AiSpeechProvider::getEnabled, true)
            .eq(AiSpeechProvider::getTtsEnabled, true)
            .eq(AiSpeechProvider::getDefaultTts, true)
            .last("limit 1"));
        if (provider == null) {
            throw new ServiceException("未配置默认 TTS 语音服务商");
        }
        ttsProviderRegistry.get(provider.getProviderType());
        return provider;
    }

    public AiSpeechProvider requireDefaultRecordingAsr() {
        AiSpeechProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiSpeechProvider>()
            .eq(AiSpeechProvider::getEnabled, true)
            .eq(AiSpeechProvider::getRecordingAsrEnabled, true)
            .eq(AiSpeechProvider::getDefaultRecordingAsr, true)
            .last("limit 1"));
        if (provider == null) {
            throw new ServiceException("未配置默认录音 ASR 语音服务商");
        }
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
            throw new ServiceException("未配置默认流式 ASR 语音服务商");
        }
        streamingAsrProviderRegistry.get(provider.getProviderType());
        return provider;
    }
}
