package org.dromara.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.service.StickyAgentRegistry;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 记忆坐席登记 Redis 实现。
 *
 * <p>Key 设计：{@code callnexus:queue:sticky:{tenantId}:{queueId}:{callerNumber}}，
 * 值为本次实际接听的坐席 ID；过期时间默认 24 小时，避免坐席长期离线后仍占用记忆。
 *
 * <p>查询命中时回查坐席当前在线状态（{@link AgentPresenceStatus#IDLE}）和分机所属节点，
 * 任一条件不满足都返回 null，由调用方回落到正常队列分配。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StickyAgentRegistryImpl implements StickyAgentRegistry {

    private static final String STICKY_KEY_PREFIX = "callnexus:queue:sticky:";
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";
    private static final Duration STICKY_TTL = Duration.ofHours(24);

    private final AgentExtensionMapper extensionMapper;
    private final SipAccountQueryService sipAccountQueryService;

    @Override
    public String findStickyAgentTarget(String tenantId, Long queueId, String callerNumber, Long nodeId) {
        if (StringUtils.isBlank(tenantId) || queueId == null || StringUtils.isBlank(callerNumber) || nodeId == null) {
            return null;
        }
        String normalizedCaller = normalizeCallerNumber(callerNumber);
        Long agentId = RedisUtils.getCacheObject(stickyKey(tenantId, queueId, normalizedCaller));
        if (agentId == null) {
            log.info("队列记忆坐席未命中，tenantId={}，queueId={}，callerNumber={}，normalizedCaller={}",
                tenantId, queueId, callerNumber, normalizedCaller);
            return null;
        }

        AgentPresence presence = RedisUtils.getCacheObject(PRESENCE_KEY_PREFIX + tenantId + ":" + agentId);
        if (presence == null || presence.getStatus() != AgentPresenceStatus.IDLE) {
            log.info("记忆坐席不可用（坐席不在线或忙），回落至普通队列分配，tenantId={}，queueId={}，callerNumber={}，agentId={}，status={}",
                tenantId, queueId, normalizedCaller, agentId, presence == null ? "OFFLINE" : presence.getStatus());
            return null;
        }
        String target = TenantHelper.ignore(() -> resolveBridgeTarget(agentId, nodeId, tenantId, queueId, normalizedCaller));
        if (StringUtils.isNotBlank(target)) {
            log.info("队列记忆坐席命中，tenantId={}，queueId={}，callerNumber={}，normalizedCaller={}，agentId={}，target={}",
                tenantId, queueId, callerNumber, normalizedCaller, agentId, target);
        }
        return target;
    }

    @Override
    public void recordStickyAgent(String tenantId, Long queueId, String callerNumber, Long agentId) {
        if (StringUtils.isBlank(tenantId) || queueId == null || StringUtils.isBlank(callerNumber) || agentId == null) {
            log.info("跳过登记队列记忆坐席，参数不完整，tenantId={}，queueId={}，callerNumber={}，agentId={}",
                tenantId, queueId, callerNumber, agentId);
            return;
        }
        String normalizedCaller = normalizeCallerNumber(callerNumber);
        try {
            RedisUtils.setCacheObject(stickyKey(tenantId, queueId, normalizedCaller), agentId, STICKY_TTL);
            log.info("已登记队列记忆坐席，tenantId={}，queueId={}，callerNumber={}，normalizedCaller={}，agentId={}",
                tenantId, queueId, callerNumber, normalizedCaller, agentId);
        } catch (Exception exception) {
            log.warn("登记队列记忆坐席失败，不影响通话流程，tenantId={}，queueId={}，callerNumber={}，normalizedCaller={}，agentId={}",
                tenantId, queueId, callerNumber, normalizedCaller, agentId, exception);
        }
    }

    private String resolveBridgeTarget(Long agentId, Long nodeId, String tenantId, Long queueId, String callerNumber) {
        AgentExtension binding = extensionMapper.selectOne(new LambdaQueryWrapper<AgentExtension>()
            .eq(AgentExtension::getAgentId, agentId)
            .last("limit 1"));
        if (binding == null || binding.getSipAccountId() == null) {
            log.info("记忆坐席未绑定分机，回落至普通队列分配，tenantId={}，queueId={}，callerNumber={}，agentId={}",
                tenantId, queueId, callerNumber, agentId);
            return null;
        }
        SipAccountResponse account = sipAccountQueryService.get(binding.getSipAccountId());
        if (account == null || !Boolean.TRUE.equals(account.getEnabled())
            || !nodeId.equals(account.getNodeId())
            || StringUtils.isBlank(account.getExtension())
            || StringUtils.isBlank(account.getDomain())) {
            log.info("记忆坐席分机不可用或跨节点，回落至普通队列分配，tenantId={}，queueId={}，callerNumber={}，agentId={}，nodeId={}",
                tenantId, queueId, callerNumber, agentId, nodeId);
            return null;
        }
        return account.getExtension() + "@" + account.getDomain();
    }

    private String stickyKey(String tenantId, Long queueId, String callerNumber) {
        return STICKY_KEY_PREFIX + tenantId + ":" + queueId + ":" + callerNumber;
    }

    private String normalizeCallerNumber(String callerNumber) {
        if (StringUtils.isBlank(callerNumber)) {
            return callerNumber;
        }
        String normalized = callerNumber.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "");
        if (normalized.startsWith("+86") && normalized.length() > 3) {
            normalized = normalized.substring(3);
        } else if (normalized.startsWith("0086") && normalized.length() > 4) {
            normalized = normalized.substring(4);
        } else if (normalized.startsWith("86") && normalized.length() > 11) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
