package org.dromara.ivr.compiler;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.service.AiSpeechProviderSelector;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TtsPlaybackNodeCompiler implements IvrNodeCompiler {

    private static final int MAX_TEXT_LENGTH = 1000;

    private final AiKnowledgeProperties properties;
    private final AiSpeechProviderSelector speechProviderSelector;

    @Override
    public String nodeType() {
        return "TTS_PLAYBACK";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        requireText(context.node().config().path("text").asText());
        requireProfile();
        defaultTtsProvider();
        context.requireSingleDefaultRoute();
    }

    @Override
    public void compile(IvrNodeContext context) {
        String text = requireText(context.node().config().path("text").asText());
        AiSpeechProvider provider = defaultTtsProvider();
        String profile = requireProfile();
        String voice = StringUtils.isBlank(provider.getDefaultVoice())
            ? properties.getUnimrcp().getVoice()
            : provider.getDefaultVoice();
        String speakData = cleanSegment(profile) + "|" + cleanSegment(voice) + "|" + cleanSegment(text);

        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"speak\" data=\"")
            .append(context.renderSupport().escape(speakData))
            .append("\"/>\n");
        context.renderSupport().appendTransfer(
            context.xml(), context.flow().getId(), context.graph().defaultTarget(context.node().id()));
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private AiSpeechProvider defaultTtsProvider() {
        try {
            return speechProviderSelector.requireDefaultStreamingTts();
        } catch (ServiceException exception) {
            return speechProviderSelector.requireDefaultTts();
        }
    }

    private String requireProfile() {
        String profile = properties.getUnimrcp().getProfile();
        if (StringUtils.isBlank(profile)) {
            throw new ServiceException("IVR 播放文字需要配置 UniMRCP TTS Profile");
        }
        return profile.trim();
    }

    private String requireText(String value) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("请填写 IVR 播放文字内容");
        }
        String text = value.trim();
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new ServiceException("IVR 播放文字不能超过 " + MAX_TEXT_LENGTH + " 个字符");
        }
        return text;
    }

    private String cleanSegment(String value) {
        return value == null ? "" : value.replace('|', '，')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim();
    }
}
