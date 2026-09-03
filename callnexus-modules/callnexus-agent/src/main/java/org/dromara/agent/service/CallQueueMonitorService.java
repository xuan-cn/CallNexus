package org.dromara.agent.service;

import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CallQueueAgentStatusResponse;
import org.dromara.agent.domain.response.CallQueueMonitorOverviewResponse;
import org.dromara.agent.domain.response.CallQueueMonitorResponse;
import org.dromara.agent.domain.response.CallQueueRecentCallResponse;
import org.dromara.agent.domain.response.CallQueueRecentEventResponse;
import org.dromara.agent.domain.response.CallQueueTrendPointResponse;
import org.dromara.agent.mapper.CallQueueMonitorMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CallQueueMonitorService {
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";

    private final CallQueueMonitorMapper monitorMapper;

    public List<CallQueueMonitorResponse> list() {
        return list(null, null);
    }

    public List<CallQueueMonitorResponse> list(LocalDate beginDate, LocalDate endDate) {
        String tenantId = LoginHelper.getTenantId();
        LocalDateTime[] range = resolveRange(beginDate, endDate);
        List<CallQueueMonitorResponse> queues = monitorMapper.selectMonitorList(tenantId, range[0], range[1], LocalDateTime.now());
        queues.forEach(queue -> fillRuntimeFields(tenantId, queue));
        return queues;
    }

    public CallQueueMonitorResponse detail(Long queueId) {
        return list().stream()
            .filter(queue -> queueId.equals(queue.getQueueId()))
            .findFirst()
            .orElseThrow(() -> new ServiceException("呼叫队列不存在"));
    }

    public CallQueueMonitorOverviewResponse overview() {
        return overview(null, null);
    }

    public CallQueueMonitorOverviewResponse overview(LocalDate beginDate, LocalDate endDate) {
        List<CallQueueMonitorResponse> queues = list(beginDate, endDate);
        CallQueueMonitorOverviewResponse response = new CallQueueMonitorOverviewResponse();
        response.setQueueCount((long) queues.size());
        response.setHealthyQueueCount(countHealth(queues, "NORMAL"));
        response.setWarningQueueCount(countHealth(queues, "WARNING"));
        response.setAbnormalQueueCount(countHealth(queues, "ABNORMAL"));
        response.setCurrentWaitingCount(sum(queues, CallQueueMonitorResponse::getWaitingCount));
        response.setCurrentRingingCount(sum(queues, CallQueueMonitorResponse::getRingingCount));
        response.setTotalAgentCount(sum(queues, CallQueueMonitorResponse::getTotalAgentCount));
        response.setOnlineAgentCount(sum(queues, CallQueueMonitorResponse::getOnlineAgentCount));
        response.setIdleAgentCount(sum(queues, CallQueueMonitorResponse::getIdleAgentCount));
        response.setBusyAgentCount(sum(queues, CallQueueMonitorResponse::getBusyAgentCount));
        response.setTodayEnteredCount(sum(queues, CallQueueMonitorResponse::getEnteredCount));
        response.setTodayAnsweredCount(sum(queues, CallQueueMonitorResponse::getAnsweredCount));
        response.setTodayAbandonedCount(sum(queues, CallQueueMonitorResponse::getAbandonedCount));
        response.setTodayTimeoutCount(sum(queues, CallQueueMonitorResponse::getTimeoutCount));
        response.setAverageWaitSeconds(averagePositive(queues, CallQueueMonitorResponse::getAverageWaitSeconds));
        response.setLongestWaitSeconds(max(queues, CallQueueMonitorResponse::getLongestWaitSeconds));
        response.setAnswerRate(rate(response.getTodayAnsweredCount(), response.getTodayEnteredCount()));
        response.setAbandonRate(rate(response.getTodayAbandonedCount(), response.getTodayEnteredCount()));
        return response;
    }

    private LocalDateTime[] resolveRange(LocalDate beginDate, LocalDate endDate) {
        LocalDate begin = beginDate == null ? LocalDate.now() : beginDate;
        LocalDate end = endDate == null ? begin : endDate;
        if (end.isBefore(begin)) {
            LocalDate swap = begin;
            begin = end;
            end = swap;
        }
        return new LocalDateTime[]{begin.atStartOfDay(), end.plusDays(1).atStartOfDay()};
    }

    public List<CallQueueAgentStatusResponse> agents(Long queueId) {
        String tenantId = LoginHelper.getTenantId();
        List<CallQueueAgentStatusResponse> agents = monitorMapper.selectQueueAgents(tenantId, queueId);
        agents.forEach(agent -> fillAgentPresence(tenantId, agent));
        return agents;
    }

    public List<CallQueueTrendPointResponse> trend(Long queueId, LocalDate date) {
        String tenantId = LoginHelper.getTenantId();
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        LocalDateTime startAt = targetDate.atStartOfDay();
        LocalDateTime endAt = startAt.plusDays(1);
        Map<Integer, CallQueueTrendPointResponse> actual = monitorMapper.selectTrend(tenantId, queueId, startAt, endAt)
            .stream()
            .collect(Collectors.toMap(CallQueueTrendPointResponse::getHour, Function.identity(), (left, right) -> left));
        List<CallQueueTrendPointResponse> points = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            CallQueueTrendPointResponse point = actual.get(hour);
            if (point == null) {
                point = new CallQueueTrendPointResponse();
                point.setHour(hour);
                point.setEnteredCount(0L);
                point.setAnsweredCount(0L);
                point.setAbandonedCount(0L);
                point.setTimeoutCount(0L);
            }
            points.add(point);
        }
        return points;
    }

    public List<CallQueueRecentEventResponse> recentEvents(Long queueId, Integer limit) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return monitorMapper.selectRecentEvents(LoginHelper.getTenantId(), queueId, safeLimit)
            .stream()
            .peek(event -> event.setEventText(eventText(event.getEventType())))
            .toList();
    }

    public List<CallQueueRecentCallResponse> recentCalls(Long queueId, Integer limit) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return monitorMapper.selectRecentCalls(LoginHelper.getTenantId(), queueId, safeLimit);
    }

    private void fillRuntimeFields(String tenantId, CallQueueMonitorResponse queue) {
        if (!hasSyncError(queue)) {
            queue.setSyncError(null);
        }
        List<CallQueueAgentStatusResponse> agents = monitorMapper.selectQueueAgents(tenantId, queue.getQueueId());
        agents.forEach(agent -> fillAgentPresence(tenantId, agent));
        long online = agents.stream().filter(agent -> !"OFFLINE".equals(agent.getStatus())).count();
        long idle = agents.stream().filter(agent -> "IDLE".equals(agent.getStatus())).count();
        long busy = agents.stream().filter(agent -> "BUSY".equals(agent.getStatus()) || "AFTER_CALL".equals(agent.getStatus())).count();
        queue.setTotalAgentCount((long) agents.size());
        queue.setOnlineAgentCount(online);
        queue.setIdleAgentCount(idle);
        queue.setBusyAgentCount(busy);
        queue.setOfflineAgentCount(Math.max(0, agents.size() - online));
        queue.setAnswerRate(rate(queue.getAnsweredCount(), queue.getEnteredCount()));
        queue.setAbandonRate(rate(queue.getAbandonedCount(), queue.getEnteredCount()));
        fillHealth(queue);
    }

    private void fillAgentPresence(String tenantId, CallQueueAgentStatusResponse agent) {
        AgentPresence presence = RedisUtils.getCacheObject(PRESENCE_KEY_PREFIX + tenantId + ":" + agent.getAgentId());
        AgentPresenceStatus status = presence == null ? AgentPresenceStatus.OFFLINE : presence.getStatus();
        agent.setStatus(status.name());
        agent.setStatusText(statusText(status));
        agent.setAssignable(Boolean.TRUE.equals(agent.getEnabled()) && status == AgentPresenceStatus.IDLE);
        if (presence != null) {
            agent.setSignedInAt(presence.getSignedInAt());
            agent.setUpdatedAt(presence.getUpdatedAt());
        }
    }

    private void fillHealth(CallQueueMonitorResponse queue) {
        boolean syncFailed = hasSyncError(queue);
        boolean waitingWithoutIdleAgent = value(queue.getWaitingCount()) > 0 && value(queue.getIdleAgentCount()) == 0;
        boolean waitNearLimit = queue.getMaxWaitSeconds() != null
            && queue.getMaxWaitSeconds() > 0
            && value(queue.getLongestWaitSeconds()) >= Math.round(queue.getMaxWaitSeconds() * 0.8);
        if (syncFailed || waitingWithoutIdleAgent || waitNearLimit || !Boolean.TRUE.equals(queue.getEnabled())) {
            queue.setHealthStatus("ABNORMAL");
            queue.setHealthText(syncFailed ? "同步异常" : !Boolean.TRUE.equals(queue.getEnabled()) ? "已停用" : waitingWithoutIdleAgent ? "排队中但无空闲坐席" : "等待接近超时");
            return;
        }
        boolean hasWaiting = value(queue.getWaitingCount()) > 0;
        boolean lowAnswerRate = value(queue.getEnteredCount()) >= 10 && value(queue.getAnswerRate()) < 70;
        boolean highAbandonRate = value(queue.getEnteredCount()) >= 10 && value(queue.getAbandonRate()) > 20;
        if (hasWaiting || lowAnswerRate || highAbandonRate) {
            queue.setHealthStatus("WARNING");
            queue.setHealthText(hasWaiting ? "有客户排队" : lowAnswerRate ? "接通率偏低" : "放弃率偏高");
            return;
        }
        queue.setHealthStatus("NORMAL");
        queue.setHealthText("运行正常");
    }

    private boolean hasSyncError(CallQueueMonitorResponse queue) {
        return ("FAILED".equals(queue.getSyncStatus()) || "PARTIAL".equals(queue.getSyncStatus()))
            && queue.getSyncError() != null && !queue.getSyncError().isBlank();
    }

    private String statusText(AgentPresenceStatus status) {
        return switch (status) {
            case IDLE -> "空闲";
            case BUSY -> "通话中";
            case AFTER_CALL -> "话后整理";
            case OFFLINE -> "离线";
        };
    }

    private String eventText(String eventType) {
        if (eventType == null) return "未知事件";
        return switch (eventType) {
            case "QUEUE_IN" -> "进入队列";
            case "QUEUE_WAIT" -> "队列等待";
            case "AGENT_RING" -> "坐席振铃";
            case "AGENT_ANSWER" -> "坐席接听";
            case "QUEUE_TIMEOUT" -> "队列超时";
            case "ABANDON" -> "客户放弃";
            case "AGENT_NO_ANSWER" -> "坐席未接";
            default -> eventType;
        };
    }

    private long countHealth(List<CallQueueMonitorResponse> queues, String healthStatus) {
        return queues.stream().filter(queue -> healthStatus.equals(queue.getHealthStatus())).count();
    }

    private long sum(List<CallQueueMonitorResponse> queues, Function<CallQueueMonitorResponse, Long> getter) {
        return queues.stream().map(getter).mapToLong(this::value).sum();
    }

    private long max(List<CallQueueMonitorResponse> queues, Function<CallQueueMonitorResponse, Long> getter) {
        return queues.stream().map(getter).filter(item -> item != null).max(Comparator.naturalOrder()).orElse(0L);
    }

    private long averagePositive(List<CallQueueMonitorResponse> queues, Function<CallQueueMonitorResponse, Long> getter) {
        return Math.round(queues.stream().map(getter).mapToLong(this::value).filter(item -> item > 0).average().orElse(0));
    }

    private long rate(Long numerator, Long denominator) {
        long denominatorValue = value(denominator);
        if (denominatorValue == 0) return 0;
        return Math.round(value(numerator) * 100.0 / denominatorValue);
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }
}
