package org.dromara.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.realtime.AiRealtimeTokenService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.ai.service.AiAgentDialplanQueryService;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiRealtimeDialplanService implements AiAgentDialplanQueryService {
    private final AiKnowledgeProperties properties;
    private final AiAgentMapper agentMapper;
    private final AiSpeechProviderSelector speechProviderSelector;
    private final AiRealtimeTokenService tokenService;

    public void validate(Long agentId) {
        if (!Boolean.TRUE.equals(properties.getRealtimeEnabled())) {
            throw new ServiceException("AI 实时语音未启用，请配置 CALLNEXUS_AI_REALTIME_ENABLED=true");
        }
        if (isAudioStreamTransport() && StringUtils.isBlank(properties.getRealtimeWebsocketUrl())) {
            throw new ServiceException("AI 实时音频 WebSocket 地址未配置");
        }
        requireAgent(agentId);
        speechProviderSelector.requireDefaultStreamingAsr();
        speechProviderSelector.requireDefaultTts();
    }

    public String buildStreamUrl(Long agentId, Long flowId, Long nodeId) {
        validate(agentId);
        String token = tokenService.issue(TenantHelper.getTenantId(), agentId, flowId, nodeId);
        String separator = properties.getRealtimeWebsocketUrl().contains("?") ? "&" : "?";
        return properties.getRealtimeWebsocketUrl() + separator + "token=" + token
            + "&businessCallId=${callnexus_business_call_id}&customerLegUuid=${uuid}";
    }

    public UniMrcpOpeningPrompt buildUniMrcpOpeningPrompt(Long agentId) {
        validate(agentId);
        AiAgent agent = requireAgent(agentId);
        String text = StringUtils.isBlank(agent.getWelcomeMessage())
            ? "您好，我是" + agent.getAgentName() + "，请问有什么可以帮您？"
            : agent.getWelcomeMessage().trim();
        AiSpeechProvider provider = defaultRealtimeTtsProvider();
        String voice = StringUtils.isBlank(provider.getDefaultVoice())
            ? properties.getUnimrcp().getVoice()
            : provider.getDefaultVoice();
        return new UniMrcpOpeningPrompt(properties.getUnimrcp().getProfile(), voice, text);
    }

    public boolean isUniMrcpTransport() {
        return "UNIMRCP".equalsIgnoreCase(properties.getRealtimeTransport());
    }

    public boolean isAudioStreamTransport() {
        return !isUniMrcpTransport();
    }

    @Override
    public String renderDirectAgent(String tenantId, Long agentId, Long nodeId, String destinationNumber,
                                    String context, String sipDomain) {
        return TenantHelper.dynamic(tenantId, () -> renderDirectAgentInTenant(
            agentId, nodeId, destinationNumber, context));
    }

    private String renderDirectAgentInTenant(Long agentId, Long nodeId, String destinationNumber, String context) {
        validate(agentId);
        String destination = FreeSwitchXmlRenderer.escape(destinationNumber);
        String dialplanContext = FreeSwitchXmlRenderer.escape(
            StringUtils.isBlank(context) ? "default" : context);
        StringBuilder actions = new StringBuilder()
            .append("      <action application=\"export\" data=\"callnexus_ai_active=true\"/>\n")
            .append("      <action application=\"export\" data=\"callnexus_ai_agent_id=")
            .append(agentId).append("\"/>\n")
              .append("      <action application=\"export\" data=\"callnexus_ai_flow_id=0\"/>\n")
              .append("      <action application=\"export\" data=\"callnexus_ai_node_id=")
              .append(nodeId == null ? 0L : nodeId).append("\"/>\n")
            .append("      <action application=\"export\" data=\"callnexus_ai_customer_leg_uuid=${uuid}\"/>\n");
        if (isUniMrcpTransport()) {
            UniMrcpOpeningPrompt opening = buildUniMrcpOpeningPrompt(agentId);
            actions.append("      <action application=\"export\" data=\"callnexus_ai_transport=UNIMRCP\"/>\n");
            if (opening.hasText()) {
                String speak = cleanSegment(opening.profile()) + "|" + cleanSegment(opening.voice()) + "|"
                    + cleanSegment(opening.text());
                actions.append("      <action application=\"export\" data=\"callnexus_ai_opening_preplayed=true\"/>\n")
                    .append("      <action application=\"speak\" data=\"")
                    .append(FreeSwitchXmlRenderer.escape(speak)).append("\"/>\n");
            }
        } else {
            String streamUrl = buildStreamUrl(agentId, 0L, nodeId);
            actions.append("      <action application=\"set\" data=\"STREAM_BUFFER_SIZE=100\"/>\n")
                .append("      <action application=\"set\" data=\"STREAM_HEART_BEAT=30\"/>\n")
                .append("      <action application=\"set\" data=\"callnexus_ai_stream_result=${api(uuid_audio_stream ${uuid} start ")
                .append(FreeSwitchXmlRenderer.escape(streamUrl)).append(" mono 16k)}\"/>\n");
        }
        actions.append("      <action application=\"park\"/>\n");
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Direct AI Dialplan">
                <context name="%s">
                  <extension name="%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
            %s                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, destination, destination, actions);
    }

    private String cleanSegment(String value) {
        return value == null ? "" : value.replace('|', '，').trim();
    }

    private AiAgent requireAgent(Long agentId) {
        AiAgent agent = agentId == null ? null : agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
            .eq(AiAgent::getId, agentId)
            .eq(AiAgent::getEnabled, true)
            .last("limit 1"));
        if (agent == null) {
            throw new ServiceException("请选择已启用的 AI 助手");
        }
        return agent;
    }

    private AiSpeechProvider defaultRealtimeTtsProvider() {
        try {
            return speechProviderSelector.requireDefaultStreamingTts();
        } catch (ServiceException exception) {
            return speechProviderSelector.requireDefaultTts();
        }
    }

    public record UniMrcpOpeningPrompt(String profile, String voice, String text) {
        public boolean hasText() {
            return StringUtils.isNotBlank(text);
        }
    }
}
