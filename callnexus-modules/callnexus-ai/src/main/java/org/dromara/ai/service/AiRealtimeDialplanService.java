package org.dromara.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.realtime.AiRealtimeTokenService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiRealtimeDialplanService {
    private final AiKnowledgeProperties properties;
    private final AiAgentMapper agentMapper;
    private final AiSpeechProviderSelector speechProviderSelector;
    private final AiRealtimeTokenService tokenService;

    public void validate(Long agentId) {
        if (!Boolean.TRUE.equals(properties.getRealtimeEnabled())) {
            throw new ServiceException("AI 实时语音未启用，请配置 CALLNEXUS_AI_REALTIME_ENABLED=true");
        }
        if (StringUtils.isBlank(properties.getRealtimeWebsocketUrl())) {
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
}
