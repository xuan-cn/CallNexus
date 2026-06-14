package org.dromara.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.CallQueue;
import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.mapper.CallQueueMapper;
import org.dromara.agent.mapper.SkillGroupMemberMapper;
import org.dromara.agent.runtime.AgentQueueRuntimeStatus;
import org.dromara.agent.service.CallQueueRuntimeSyncService;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.agent.service.HandlingQueueResolver;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.domain.response.SipRegistrationConfigResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CurrentAgentSessionServiceImpl implements CurrentAgentSessionService {
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";
    private static final String ACTIVE_CALL_KEY_PREFIX = "callnexus:agent:active-call:";
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);

    private final AgentMapper agentMapper;
    private final AgentExtensionMapper extensionMapper;
    private final SkillGroupMemberMapper skillGroupMemberMapper;
    private final CallQueueMapper callQueueMapper;
    private final SipAccountQueryService sipAccountQueryService;
    private final CallQueueRuntimeSyncService queueRuntimeSyncService;
    private final HandlingQueueResolver handlingQueueResolver;

    @Override
    public CurrentAgentResponse current() {
        Agent agent = findCurrentAgent();
        if (agent == null) {
            CurrentAgentResponse response = new CurrentAgentResponse();
            response.setConfigured(false);
            response.setStatus(AgentPresenceStatus.OFFLINE);
            return response;
        }
        return buildResponse(agent, normalizeAfterCallStatus(agent, getPresence(agent.getId())));
    }

    @Override
    public CurrentAgentResponse signIn() {
        Agent agent = requireCurrentAgent();
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new ServiceException("坐席已停用");
        }
        requireEnabledSipAccount(agent.getId());
        LocalDateTime now = LocalDateTime.now();
        AgentPresence presence = new AgentPresence();
        presence.setAgentId(agent.getId());
        presence.setStatus(AgentPresenceStatus.IDLE);
        presence.setSignedInAt(now);
        presence.setUpdatedAt(now);
        savePresence(agent.getId(), presence);
        syncQueueStatus(agent, AgentPresenceStatus.IDLE);
        return buildResponse(agent, presence);
    }

    @Override
    public CurrentAgentResponse changeStatus(AgentPresenceStatus status) {
        if (status == AgentPresenceStatus.OFFLINE) {
            throw new ServiceException("离线状态请使用签出操作");
        }
        Agent agent = requireCurrentAgent();
        AgentPresence presence = getPresence(agent.getId());
        if (presence == null) {
            throw new ServiceException("坐席未签入，请先签入");
        }
        presence.setStatus(status);
        presence.setUpdatedAt(LocalDateTime.now());
        savePresence(agent.getId(), presence);
        syncQueueStatus(agent, status);
        return buildResponse(agent, presence);
    }

    @Override
    public void signOut() {
        Agent agent = requireCurrentAgent();
        syncQueueStatus(agent, AgentPresenceStatus.OFFLINE);
        RedisUtils.deleteObject(presenceKey(agent.getId()));
    }

    private Agent findCurrentAgent() {
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
            .eq(Agent::getUserId, LoginHelper.getUserId()));
    }

    private Agent requireCurrentAgent() {
        Agent agent = findCurrentAgent();
        if (agent == null) {
            throw new ServiceException("当前用户尚未绑定坐席");
        }
        return agent;
    }

    private void requireEnabledSipAccount(Long agentId) {
        AgentExtension binding = findExtension(agentId);
        if (binding == null || !sipAccountQueryService.existsEnabled(binding.getSipAccountId())) {
            throw new ServiceException("坐席未绑定 SIP 分机或分机已停用");
        }
        sipAccountQueryService.getRegistrationConfig(binding.getSipAccountId());
    }

    private AgentExtension findExtension(Long agentId) {
        return extensionMapper.selectOne(new LambdaQueryWrapper<AgentExtension>()
            .eq(AgentExtension::getAgentId, agentId));
    }

    private AgentPresence getPresence(Long agentId) {
        return RedisUtils.getCacheObject(presenceKey(agentId));
    }

    private void savePresence(Long agentId, AgentPresence presence) {
        RedisUtils.setCacheObject(presenceKey(agentId), presence, PRESENCE_TTL);
    }

    private String presenceKey(Long agentId) {
        return PRESENCE_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
    }

    private CurrentAgentResponse buildResponse(Agent agent, AgentPresence presence) {
        CurrentAgentResponse response = new CurrentAgentResponse();
        response.setConfigured(true);
        response.setAgentId(agent.getId());
        response.setAgentCode(agent.getAgentCode());
        response.setAgentName(agent.getAgentName());
        response.setUserId(agent.getUserId());
        AgentExtension binding = findExtension(agent.getId());
        if (binding != null) {
            response.setSipAccountId(binding.getSipAccountId());
            SipAccountResponse sipAccount = sipAccountQueryService.get(binding.getSipAccountId());
            if (sipAccount != null) {
                response.setExtension(sipAccount.getExtension());
                response.setSipDisplayName(sipAccount.getDisplayName());
            }
            if (sipAccount != null && sipAccount.getNodeId() != null) {
                SipRegistrationConfigResponse registrationConfig = sipAccountQueryService.getRegistrationConfig(binding.getSipAccountId());
                response.setNodeId(registrationConfig.getNodeId());
                response.setSipDomain(registrationConfig.getSipDomain());
                response.setWssUrl(registrationConfig.getWssUrl());
            }
        }
        response.setStatus(presence == null ? AgentPresenceStatus.OFFLINE : presence.getStatus());
        response.setAfterCallRemainingSeconds(afterCallRemainingSeconds(agent, presence));
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(agent.getId()));
        if (activeCall != null) {
            response.setActiveCallId(activeCall.getCallId());
            response.setActiveCallNumber(activeCall.getDestination());
        }
        if (presence != null) {
            response.setSignedInAt(presence.getSignedInAt());
            response.setUpdatedAt(presence.getUpdatedAt());
        }
        return response;
    }

    private AgentPresence normalizeAfterCallStatus(Agent agent, AgentPresence presence) {
        if (presence == null || presence.getStatus() != AgentPresenceStatus.AFTER_CALL) return presence;
        if (afterCallRemainingSeconds(agent, presence) > 0) return presence;
        presence.setStatus(AgentPresenceStatus.IDLE);
        presence.setUpdatedAt(LocalDateTime.now());
        savePresence(agent.getId(), presence);
        syncQueueStatus(agent, AgentPresenceStatus.IDLE);
        log.info("坐席话后整理计时结束，已自动恢复示闲，agentId={}", agent.getId());
        return presence;
    }

    private Long afterCallRemainingSeconds(Agent agent, AgentPresence presence) {
        if (presence == null || presence.getStatus() != AgentPresenceStatus.AFTER_CALL || presence.getUpdatedAt() == null) {
            return null;
        }
        long elapsedSeconds = Math.max(0, ChronoUnit.SECONDS.between(presence.getUpdatedAt(), LocalDateTime.now()));
        return Math.max(0, wrapUpSeconds(agent, presence) - elapsedSeconds);
    }

    private long wrapUpSeconds(Agent agent, AgentPresence presence) {
        // 优先按本次实际接听队列计算话后整理时长，替代旧的“所属启用队列最长时间”规则。
        // 非队列来电（如直拨分机、IVR 转分机）查不到 handling_queue 时，回退到旧规则。
        Integer handlingWrapUp = resolveHandlingQueueWrapUpSeconds(presence);
        if (handlingWrapUp != null) {
            return handlingWrapUp;
        }
        List<Long> skillGroupIds = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
                .eq(SkillGroupMember::getAgentId, agent.getId()))
            .stream().map(SkillGroupMember::getSkillGroupId).distinct().toList();
        if (skillGroupIds.isEmpty()) return 0;
        return callQueueMapper.selectList(new LambdaQueryWrapper<CallQueue>()
                .in(CallQueue::getSkillGroupId, skillGroupIds)
                .eq(CallQueue::getEnabled, true))
            .stream().map(CallQueue::getWrapUpSeconds)
            .filter(seconds -> seconds != null && seconds > 0)
            .mapToLong(Integer::longValue)
            .max().orElse(0);
    }

    private Integer resolveHandlingQueueWrapUpSeconds(AgentPresence presence) {
        if (presence == null || presence.getHandlingCallId() == null) return null;
        try {
            return handlingQueueResolver.resolveWrapUpSeconds(presence.getHandlingCallId());
        } catch (Exception exception) {
            log.warn("查询本次接听队列话后整理时长失败，回退到默认规则，agentId={}，handlingCallId={}，error={}",
                presence.getAgentId(), presence.getHandlingCallId(), exception.getMessage());
            return null;
        }
    }

    private String activeCallKey(Long agentId) {
        return ACTIVE_CALL_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
    }

    private void syncQueueStatus(Agent agent, AgentPresenceStatus status) {
        try {
            if (!skillGroupMemberMapper.exists(new LambdaQueryWrapper<SkillGroupMember>().eq(SkillGroupMember::getAgentId, agent.getId()))) {
                return;
            }
            AgentExtension binding = findExtension(agent.getId());
            if (binding == null) return;
            SipAccountResponse sipAccount = sipAccountQueryService.get(binding.getSipAccountId());
            if (sipAccount == null || sipAccount.getNodeId() == null || sipAccount.getDomain() == null) return;
            queueRuntimeSyncService.syncAgentStatus(new AgentQueueRuntimeStatus(
                sipAccount.getNodeId(), sipAccount.getExtension(), sipAccount.getDomain(), status));
        } catch (Exception exception) {
            log.warn("同步 FreeSWITCH 队列坐席状态失败，不影响坐席本地状态，agentId={}，status={}，error={}",
                agent.getId(), status, exception.getMessage());
        }
    }
}
