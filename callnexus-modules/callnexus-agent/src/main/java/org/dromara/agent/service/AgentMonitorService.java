package org.dromara.agent.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.request.AgentMonitorPageQuery;
import org.dromara.agent.domain.response.AgentMonitorActiveCallResponse;
import org.dromara.agent.domain.response.AgentMonitorCallResponse;
import org.dromara.agent.domain.response.AgentMonitorOverviewResponse;
import org.dromara.agent.domain.response.AgentMonitorPresenceDurationResponse;
import org.dromara.agent.domain.response.AgentMonitorPresenceLogResponse;
import org.dromara.agent.domain.response.AgentMonitorResponse;
import org.dromara.agent.domain.response.AgentMonitorSkillGroupResponse;
import org.dromara.agent.domain.response.AgentMonitorStatisticsResponse;
import org.dromara.agent.mapper.AgentMonitorMapper;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentMonitorService {
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";

    private final AgentMonitorMapper monitorMapper;
    private final AgentPresenceLogService presenceLogService;

    public List<AgentMonitorSkillGroupResponse> skillGroups() {
        return monitorMapper.selectSkillGroups(LoginHelper.getTenantId());
    }

    public AgentMonitorOverviewResponse overview(AgentMonitorPageQuery query) {
        String tenantId = LoginHelper.getTenantId();
        TimeRange range = timeRange(query);
        List<AgentMonitorResponse> agents = monitorMapper.selectAgents(tenantId, null, null, null);
        agents.forEach(agent -> fillPresence(tenantId, agent));
        reconcileOfflinePresence(tenantId, agents);
        List<Long> agentIds = agents.stream().map(AgentMonitorResponse::getAgentId).toList();
        AgentMonitorStatisticsResponse statistics = aggregateStatistics(loadStatistics(tenantId, agentIds, range));
        AgentMonitorPresenceDurationResponse durations = aggregateDurations(loadDurations(tenantId, agentIds, range));

        AgentMonitorOverviewResponse response = new AgentMonitorOverviewResponse();
        response.setTotalAgentCount((long) agents.size());
        response.setOnlineAgentCount(countStatus(agents, AgentPresenceStatus.IDLE)
            + countStatus(agents, AgentPresenceStatus.NOT_READY)
            + countStatus(agents, AgentPresenceStatus.BUSY)
            + countStatus(agents, AgentPresenceStatus.AFTER_CALL));
        response.setIdleAgentCount(countStatus(agents, AgentPresenceStatus.IDLE));
        response.setNotReadyAgentCount(countStatus(agents, AgentPresenceStatus.NOT_READY));
        response.setBusyAgentCount(countStatus(agents, AgentPresenceStatus.BUSY));
        response.setAfterCallAgentCount(countStatus(agents, AgentPresenceStatus.AFTER_CALL));
        response.setOfflineAgentCount(countStatus(agents, AgentPresenceStatus.OFFLINE));
        response.setTodayHandledCount(statistics.getTodayHandledCount());
        response.setInboundAnsweredCount(statistics.getInboundAnsweredCount());
        response.setOutboundAnsweredCount(statistics.getOutboundAnsweredCount());
        response.setMissedOfferCount(statistics.getMissedOfferCount());
        response.setTotalTalkSeconds(statistics.getTotalTalkSeconds());
        response.setAverageTalkSeconds(statistics.getAverageTalkSeconds());
        fillDurations(response, durations);
        return response;
    }

    public TableDataInfo<AgentMonitorResponse> page(AgentMonitorPageQuery query, PageQuery pageQuery) {
        String tenantId = LoginHelper.getTenantId();
        List<AgentMonitorResponse> agents = monitorMapper.selectAgents(
            tenantId, trim(query.getKeyword()), query.getSkillGroupId(), query.getEnabled());
        agents.forEach(agent -> fillPresence(tenantId, agent));
        reconcileOfflinePresence(tenantId, agents);

        String expectedStatus = normalizeStatus(query.getStatus());
        if (expectedStatus != null) {
            agents = agents.stream().filter(agent -> expectedStatus.equals(agent.getStatus())).toList();
        }

        long total = agents.size();
        int pageNum = pageQuery.getPageNum() == null || pageQuery.getPageNum() < 1 ? 1 : pageQuery.getPageNum();
        int pageSize = pageQuery.getPageSize() == null || pageQuery.getPageSize() < 1 ? 20 : pageQuery.getPageSize();
        int fromIndex = Math.min((pageNum - 1) * pageSize, agents.size());
        int toIndex = Math.min(fromIndex + pageSize, agents.size());
        List<AgentMonitorResponse> records = agents.subList(fromIndex, toIndex);
        fillCallData(tenantId, records, timeRange(query));
        return new TableDataInfo<>(records, total);
    }

    public TableDataInfo<AgentMonitorCallResponse> recentCalls(Long agentId, PageQuery pageQuery) {
        Page<AgentMonitorCallResponse> page = monitorMapper.selectRecentCalls(
            pageQuery.build(), LoginHelper.getTenantId(), agentId);
        return TableDataInfo.build(page);
    }

    public TableDataInfo<AgentMonitorPresenceLogResponse> presenceLogs(Long agentId,
                                                                       AgentMonitorPageQuery query,
                                                                       PageQuery pageQuery) {
        TimeRange range = timeRange(query);
        Page<AgentMonitorPresenceLogResponse> page = monitorMapper.selectPresenceLogs(
            pageQuery.build(), LoginHelper.getTenantId(), agentId, range.startAt(), range.endAt());
        return TableDataInfo.build(page);
    }

    private void fillCallData(String tenantId, List<AgentMonitorResponse> agents, TimeRange range) {
        if (agents.isEmpty()) return;
        List<Long> agentIds = agents.stream().map(AgentMonitorResponse::getAgentId).toList();
        Map<Long, AgentMonitorStatisticsResponse> statistics = loadStatistics(tenantId, agentIds, range).stream()
            .collect(Collectors.toMap(AgentMonitorStatisticsResponse::getAgentId, Function.identity()));
        Map<Long, AgentMonitorPresenceDurationResponse> durations = loadDurations(tenantId, agentIds, range).stream()
            .collect(Collectors.toMap(AgentMonitorPresenceDurationResponse::getAgentId, Function.identity()));
        Map<Long, AgentMonitorActiveCallResponse> activeCalls = new LinkedHashMap<>();
        for (AgentMonitorActiveCallResponse activeCall : monitorMapper.selectActiveCalls(tenantId, agentIds)) {
            activeCalls.putIfAbsent(activeCall.getAgentId(), activeCall);
        }
        agents.forEach(agent -> {
            AgentMonitorStatisticsResponse stat = statistics.get(agent.getAgentId());
            if (stat != null) fillStatistics(agent, stat);
            else fillStatistics(agent, emptyStatistics(agent.getAgentId()));
            fillDurations(agent, durations.getOrDefault(agent.getAgentId(), emptyDurations(agent.getAgentId())));
            AgentMonitorActiveCallResponse activeCall = activeCalls.get(agent.getAgentId());
            if (activeCall != null) {
                agent.setCurrentBusinessCallId(activeCall.getBusinessCallId());
                agent.setCurrentDirection(activeCall.getDirection());
                agent.setCurrentPeerNumber(activeCall.getPeerNumber());
                agent.setCurrentCallState(activeCall.getCallState());
                agent.setCurrentCallStartedAt(activeCall.getStartedAt());
            }
        });
    }

    private List<AgentMonitorStatisticsResponse> loadStatistics(String tenantId, List<Long> agentIds, TimeRange range) {
        if (agentIds.isEmpty()) return Collections.emptyList();
        return monitorMapper.selectStatistics(tenantId, agentIds, range.startAt(), range.endAt(), range.nowAt());
    }

    private List<AgentMonitorPresenceDurationResponse> loadDurations(String tenantId, List<Long> agentIds, TimeRange range) {
        if (agentIds.isEmpty()) return Collections.emptyList();
        return monitorMapper.selectPresenceDurations(tenantId, agentIds, range.startAt(), range.endAt(), range.nowAt());
    }

    private void fillPresence(String tenantId, AgentMonitorResponse agent) {
        AgentPresence presence = RedisUtils.getCacheObject(PRESENCE_KEY_PREFIX + tenantId + ":" + agent.getAgentId());
        AgentPresenceStatus status = presence == null || presence.getStatus() == null
            ? AgentPresenceStatus.OFFLINE : presence.getStatus();
        agent.setStatus(status.name());
        agent.setStatusText(statusText(status));
        if (presence != null) {
            agent.setSignedInAt(presence.getSignedInAt());
            agent.setStatusUpdatedAt(presence.getUpdatedAt());
        }
    }

    private void reconcileOfflinePresence(String tenantId, List<AgentMonitorResponse> agents) {
        List<Long> offlineAgentIds = agents.stream()
            .filter(agent -> AgentPresenceStatus.OFFLINE.name().equals(agent.getStatus()))
            .map(AgentMonitorResponse::getAgentId)
            .toList();
        presenceLogService.reconcileOfflineAgents(tenantId, offlineAgentIds, LocalDateTime.now());
    }

    private void fillStatistics(AgentMonitorResponse agent, AgentMonitorStatisticsResponse stat) {
        agent.setTodayHandledCount(value(stat.getTodayHandledCount()));
        agent.setInboundAnsweredCount(value(stat.getInboundAnsweredCount()));
        agent.setOutboundAnsweredCount(value(stat.getOutboundAnsweredCount()));
        agent.setMissedOfferCount(value(stat.getMissedOfferCount()));
        agent.setTotalTalkSeconds(value(stat.getTotalTalkSeconds()));
        agent.setAverageTalkSeconds(value(stat.getAverageTalkSeconds()));
    }

    private AgentMonitorStatisticsResponse aggregateStatistics(List<AgentMonitorStatisticsResponse> rows) {
        AgentMonitorStatisticsResponse result = emptyStatistics(null);
        long answeredLegs = 0;
        for (AgentMonitorStatisticsResponse row : rows) {
            result.setTodayHandledCount(result.getTodayHandledCount() + value(row.getTodayHandledCount()));
            result.setInboundAnsweredCount(result.getInboundAnsweredCount() + value(row.getInboundAnsweredCount()));
            result.setOutboundAnsweredCount(result.getOutboundAnsweredCount() + value(row.getOutboundAnsweredCount()));
            result.setMissedOfferCount(result.getMissedOfferCount() + value(row.getMissedOfferCount()));
            result.setTotalTalkSeconds(result.getTotalTalkSeconds() + value(row.getTotalTalkSeconds()));
            answeredLegs += value(row.getTodayHandledCount());
        }
        result.setAverageTalkSeconds(answeredLegs == 0 ? 0 : Math.round((double) result.getTotalTalkSeconds() / answeredLegs));
        return result;
    }

    private AgentMonitorPresenceDurationResponse aggregateDurations(List<AgentMonitorPresenceDurationResponse> rows) {
        AgentMonitorPresenceDurationResponse result = emptyDurations(null);
        rows.forEach(row -> {
            result.setOnlineSeconds(result.getOnlineSeconds() + value(row.getOnlineSeconds()));
            result.setIdleSeconds(result.getIdleSeconds() + value(row.getIdleSeconds()));
            result.setNotReadySeconds(result.getNotReadySeconds() + value(row.getNotReadySeconds()));
            result.setBusySeconds(result.getBusySeconds() + value(row.getBusySeconds()));
            result.setAfterCallSeconds(result.getAfterCallSeconds() + value(row.getAfterCallSeconds()));
        });
        return result;
    }

    private AgentMonitorPresenceDurationResponse emptyDurations(Long agentId) {
        AgentMonitorPresenceDurationResponse result = new AgentMonitorPresenceDurationResponse();
        result.setAgentId(agentId);
        result.setOnlineSeconds(0L);
        result.setIdleSeconds(0L);
        result.setNotReadySeconds(0L);
        result.setBusySeconds(0L);
        result.setAfterCallSeconds(0L);
        return result;
    }

    private void fillDurations(AgentMonitorResponse target, AgentMonitorPresenceDurationResponse durations) {
        target.setOnlineSeconds(value(durations.getOnlineSeconds()));
        target.setIdleSeconds(value(durations.getIdleSeconds()));
        target.setNotReadySeconds(value(durations.getNotReadySeconds()));
        target.setBusySeconds(value(durations.getBusySeconds()));
        target.setAfterCallSeconds(value(durations.getAfterCallSeconds()));
        target.setUtilizationRate(utilizationRate(target.getBusySeconds(), target.getOnlineSeconds()));
    }

    private void fillDurations(AgentMonitorOverviewResponse target, AgentMonitorPresenceDurationResponse durations) {
        target.setOnlineSeconds(value(durations.getOnlineSeconds()));
        target.setIdleSeconds(value(durations.getIdleSeconds()));
        target.setNotReadySeconds(value(durations.getNotReadySeconds()));
        target.setBusySeconds(value(durations.getBusySeconds()));
        target.setAfterCallSeconds(value(durations.getAfterCallSeconds()));
        target.setUtilizationRate(utilizationRate(target.getBusySeconds(), target.getOnlineSeconds()));
    }

    private double utilizationRate(long busySeconds, long onlineSeconds) {
        return onlineSeconds == 0 ? 0D : Math.round(busySeconds * 1000D / onlineSeconds) / 10D;
    }

    private TimeRange timeRange(AgentMonitorPageQuery query) {
        LocalDate beginDate = query == null || query.getBeginDate() == null ? LocalDate.now() : query.getBeginDate();
        LocalDate endDate = query == null || query.getEndDate() == null ? beginDate : query.getEndDate();
        if (endDate.isBefore(beginDate)) endDate = beginDate;
        return new TimeRange(beginDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(), LocalDateTime.now());
    }

    private record TimeRange(LocalDateTime startAt, LocalDateTime endAt, LocalDateTime nowAt) {
    }

    private AgentMonitorStatisticsResponse emptyStatistics(Long agentId) {
        AgentMonitorStatisticsResponse result = new AgentMonitorStatisticsResponse();
        result.setAgentId(agentId);
        result.setTodayHandledCount(0L);
        result.setInboundAnsweredCount(0L);
        result.setOutboundAnsweredCount(0L);
        result.setMissedOfferCount(0L);
        result.setTotalTalkSeconds(0L);
        result.setAverageTalkSeconds(0L);
        return result;
    }

    private long countStatus(List<AgentMonitorResponse> agents, AgentPresenceStatus status) {
        return agents.stream().filter(agent -> status.name().equals(agent.getStatus())).count();
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        try {
            AgentPresenceStatus.valueOf(normalized);
            return normalized;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String statusText(AgentPresenceStatus status) {
        return switch (status) {
            case IDLE -> "空闲";
            case NOT_READY -> "示忙";
            case BUSY -> "通话中";
            case AFTER_CALL -> "话后整理";
            case OFFLINE -> "离线";
        };
    }
}
