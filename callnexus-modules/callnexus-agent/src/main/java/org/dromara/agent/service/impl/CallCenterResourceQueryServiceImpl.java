package org.dromara.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.CallQueue;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.mapper.CallQueueMapper;
import org.dromara.agent.service.CallCenterResourceQueryService;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.dromara.resource.sip.domain.response.SipAccountRealtimeResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 呼叫中心资源查询契约实现。
 *
 * <p>队列事件处理需要按 FreeSWITCH 上报的队列编码和坐席标识反查业务实体，
 * 本实现由 agent 模块提供，供 call 模块的队列事件处理服务调用，避免 call 反向依赖 agent 的 Mapper。
 */
@Service
@RequiredArgsConstructor
public class CallCenterResourceQueryServiceImpl implements CallCenterResourceQueryService {
    private final CallQueueMapper callQueueMapper;
    private final FreeSwitchNodeGroupMemberMapper nodeGroupMemberMapper;
    private final SipAccountQueryService sipAccountQueryService;
    private final AgentExtensionMapper extensionMapper;
    private final AgentMapper agentMapper;

    @Override
    public QueueInfo findQueueByCode(String queueCodeWithProfile, Long nodeId) {
        if (queueCodeWithProfile == null || queueCodeWithProfile.isBlank() || nodeId == null) return null;
        String queueCode = stripProfile(queueCodeWithProfile);
        return TenantHelper.ignore(() -> {
            CallQueue queue = callQueueMapper.selectOne(new LambdaQueryWrapper<CallQueue>()
                .eq(CallQueue::getQueueCode, queueCode)
                .eq(CallQueue::getEnabled, true)
                .last("limit 1"));
            if (queue == null) return null;
            // 队列按节点组绑定，需确认当前上报节点属于该队列的节点组，避免跨节点串用配置。
            if (!nodeBelongsToGroup(queue.getNodeGroupId(), nodeId)) return null;
            return new QueueInfo(queue.getId(), queue.getQueueCode(), queue.getQueueName(), queue.getWrapUpSeconds());
        });
    }

    @Override
    public QueueInfo findQueueById(Long queueId) {
        if (queueId == null) return null;
        return TenantHelper.ignore(() -> {
            CallQueue queue = callQueueMapper.selectById(queueId);
            if (queue == null || !Boolean.TRUE.equals(queue.getEnabled())) return null;
            return new QueueInfo(queue.getId(), queue.getQueueCode(), queue.getQueueName(), queue.getWrapUpSeconds());
        });
    }

    @Override
    public Long findAgentIdByIdentity(String agentWithDomain, Long nodeId) {
        if (agentWithDomain == null || agentWithDomain.isBlank() || nodeId == null) return null;
        String extension = stripDomain(agentWithDomain);
        if (extension == null) return null;
        return TenantHelper.ignore(() -> {
            SipAccountRealtimeResponse sipAccount = sipAccountQueryService.findEnabledByNodeAndExtension(nodeId, extension);
            if (sipAccount == null) return null;
            AgentExtension binding = extensionMapper.selectOne(new LambdaQueryWrapper<AgentExtension>()
                .eq(AgentExtension::getSipAccountId, sipAccount.getSipAccountId()));
            if (binding == null) return null;
            Agent agent = agentMapper.selectById(binding.getAgentId());
            return agent != null && Boolean.TRUE.equals(agent.getEnabled()) ? agent.getId() : null;
        });
    }

    private boolean nodeBelongsToGroup(Long groupId, Long nodeId) {
        List<Long> nodeIds = nodeGroupMemberMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                .eq(FreeSwitchNodeGroupMember::getGroupId, groupId))
            .stream().map(FreeSwitchNodeGroupMember::getNodeId).toList();
        return nodeIds.contains(nodeId);
    }

    /**
     * 去除 FreeSWITCH 队列编码的 profile 后缀，例如 Q01@default -> Q01。
     */
    private String stripProfile(String value) {
        int at = value.indexOf('@');
        String stripped = at > 0 ? value.substring(0, at) : value;
        return stripped.trim();
    }

    /**
     * 去除坐席标识的域名部分，例如 1001@192.168.244.128 -> 1001。
     */
    private String stripDomain(String value) {
        int at = value.indexOf('@');
        String stripped = at > 0 ? value.substring(0, at) : value;
        stripped = stripped.trim();
        // 兼容 FreeSWITCH 偶尔把 leg_timeout 参数拼进 CC-Agent 的情况，例如 [leg_timeout=20]1001@domain。
        if (stripped.startsWith("[")) {
            int close = stripped.indexOf(']');
            if (close >= 0) stripped = stripped.substring(close + 1).trim();
        }
        return stripped.isBlank() ? null : stripped;
    }
}
