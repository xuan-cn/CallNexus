package org.dromara.agent.service;

import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.AgentPresenceChangeSource;
import org.dromara.agent.domain.AgentPresenceLog;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.mapper.AgentPresenceLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentPresenceLogService {
    private final AgentPresenceLogMapper logMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordTransition(String tenantId,
                                 Long agentId,
                                 AgentPresenceStatus status,
                                 AgentPresenceChangeSource source,
                                 String businessCallId,
                                 LocalDateTime occurredAt) {
        if (tenantId == null || agentId == null || status == null || source == null) return;
        if (logMapper.lockAgent(tenantId, agentId) == null) return;

        AgentPresenceLog current = logMapper.selectOpen(tenantId, agentId);
        if (current != null && status.name().equals(current.getStatus())) return;

        LocalDateTime changedAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
        logMapper.closeOpen(tenantId, agentId, changedAt);

        AgentPresenceLog next = new AgentPresenceLog();
        next.setTenantId(tenantId);
        next.setAgentId(agentId);
        next.setPreviousStatus(current == null ? null : current.getStatus());
        next.setStatus(status.name());
        next.setSource(source.name());
        next.setBusinessCallId(businessCallId);
        next.setStartedAt(changedAt);
        next.setDurationSeconds(0L);
        logMapper.insert(next);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void reconcileOfflineAgents(String tenantId, List<Long> offlineAgentIds, LocalDateTime occurredAt) {
        if (tenantId == null || offlineAgentIds == null || offlineAgentIds.isEmpty()) return;
        for (Long agentId : logMapper.selectOpenNonOfflineAgentIds(tenantId, offlineAgentIds)) {
            recordTransition(tenantId, agentId, AgentPresenceStatus.OFFLINE,
                AgentPresenceChangeSource.SYSTEM_AUTO, null, occurredAt);
        }
    }
}
