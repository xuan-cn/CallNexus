package org.dromara.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.outbound.domain.OutboundAttempt;
import org.dromara.outbound.domain.request.OutboundAttemptPageQuery;
import org.dromara.outbound.domain.response.OutboundAgentSummaryResponse;
import org.dromara.outbound.domain.response.OutboundDailyTrendResponse;
import org.dromara.outbound.domain.vo.OutboundAttemptExportVo;
import org.dromara.outbound.mapper.OutboundAttemptMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OutboundReportService {
    private final OutboundAttemptMapper attemptMapper;
    private final OutboundTaskMapper taskMapper;
    private final AgentMapper agentMapper;
    private final OutboundResultSuggestionService resultSuggestionService;

    public List<OutboundAgentSummaryResponse> agentSummary(OutboundAttemptPageQuery query) {
        requireTask(query.getTaskId());
        List<OutboundAttempt> attempts = attempts(query);
        Map<Long, Agent> agents = agents(attempts);
        return attempts.stream()
            .filter(attempt -> attempt.getAgentId() != null)
            .collect(Collectors.groupingBy(OutboundAttempt::getAgentId))
            .entrySet().stream()
            .map(entry -> agentSummary(entry.getKey(), agents.get(entry.getKey()), entry.getValue()))
            .sorted(Comparator.comparingLong(OutboundAgentSummaryResponse::getAttemptCount).reversed())
            .toList();
    }

    public List<OutboundDailyTrendResponse> dailyTrend(OutboundAttemptPageQuery query) {
        requireTask(query.getTaskId());
        return attempts(query).stream()
            .filter(attempt -> attempt.getStartedAt() != null)
            .collect(Collectors.groupingBy(attempt -> attempt.getStartedAt().toLocalDate(), TreeMap::new, Collectors.toList()))
            .entrySet().stream()
            .map(entry -> dailyTrend(entry.getKey(), entry.getValue()))
            .toList();
    }

    public void export(OutboundAttemptPageQuery query, HttpServletResponse response) {
        requireTask(query.getTaskId());
        List<OutboundAttempt> attempts = attempts(query);
        Map<Long, Agent> agents = agents(attempts);
        List<OutboundAttemptExportVo> rows = attempts.stream().map(attempt -> {
            Agent agent = agents.get(attempt.getAgentId());
            OutboundAttemptExportVo row = new OutboundAttemptExportVo();
            row.setTaskName(attempt.getTaskName());
            row.setCustomerName(attempt.getCustomerName());
            row.setPhoneNumber(attempt.getPhoneNumber());
            row.setAgentCode(agent == null ? null : agent.getAgentCode());
            row.setAgentName(agent == null ? null : agent.getAgentName());
            row.setAttemptNo(attempt.getAttemptNo());
            row.setStatus(statusLabel(attempt.getStatus()));
            row.setResult(resultSuggestionService.resultLabel(attempt.getResultCode()));
            row.setResultRemark(attempt.getResultRemark());
            row.setSuggestedResult(resultSuggestionService.resultLabel(attempt.getSuggestedResultCode()));
            row.setStartedAt(attempt.getStartedAt());
            row.setAnsweredAt(attempt.getAnsweredAt());
            row.setEndedAt(attempt.getEndedAt());
            row.setDurationSeconds(attempt.getDurationSeconds());
            row.setBillableSeconds(attempt.getBillableSeconds());
            row.setHangupCause(resultSuggestionService.hangupCauseLabel(attempt.getHangupCause()));
            row.setBusinessCallId(attempt.getBusinessCallId());
            return row;
        }).toList();
        ExcelUtil.exportExcel(rows, "外呼拨打明细", OutboundAttemptExportVo.class, response);
    }

    private List<OutboundAttempt> attempts(OutboundAttemptPageQuery query) {
        return attemptMapper.selectList(wrapper(query));
    }

    private LambdaQueryWrapper<OutboundAttempt> wrapper(OutboundAttemptPageQuery query) {
        return new LambdaQueryWrapper<OutboundAttempt>()
            .eq(query.getTaskId() != null, OutboundAttempt::getTaskId, query.getTaskId())
            .eq(query.getAgentId() != null, OutboundAttempt::getAgentId, query.getAgentId())
            .like(StringUtils.isNotBlank(query.getPhoneNumber()), OutboundAttempt::getPhoneNumber, query.getPhoneNumber())
            .eq(StringUtils.isNotBlank(query.getResultCode()), OutboundAttempt::getResultCode, query.getResultCode())
            .eq(StringUtils.isNotBlank(query.getSuggestedResultCode()), OutboundAttempt::getSuggestedResultCode, query.getSuggestedResultCode())
            .eq(StringUtils.isNotBlank(query.getHangupCause()), OutboundAttempt::getHangupCause, query.getHangupCause())
            .ge(query.getStartedAtBegin() != null, OutboundAttempt::getStartedAt, query.getStartedAtBegin())
            .le(query.getStartedAtEnd() != null, OutboundAttempt::getStartedAt, query.getStartedAtEnd())
            .orderByAsc(OutboundAttempt::getStartedAt);
    }

    private Map<Long, Agent> agents(List<OutboundAttempt> attempts) {
        Set<Long> ids = attempts.stream().map(OutboundAttempt::getAgentId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : agentMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(Agent::getId, Function.identity()));
    }

    private OutboundAgentSummaryResponse agentSummary(Long agentId, Agent agent, List<OutboundAttempt> attempts) {
        OutboundAgentSummaryResponse response = new OutboundAgentSummaryResponse();
        response.setAgentId(agentId);
        response.setAgentCode(agent == null ? null : agent.getAgentCode());
        response.setAgentName(agent == null ? null : agent.getAgentName());
        applyMetrics(response, attempts);
        return response;
    }

    private OutboundDailyTrendResponse dailyTrend(LocalDate date, List<OutboundAttempt> attempts) {
        OutboundDailyTrendResponse response = new OutboundDailyTrendResponse();
        response.setDate(date);
        applyMetrics(response, attempts);
        return response;
    }

    private void applyMetrics(OutboundAgentSummaryResponse response, List<OutboundAttempt> attempts) {
        long answered = attempts.stream().filter(attempt -> attempt.getAnsweredAt() != null).count();
        long connected = attempts.stream().filter(attempt -> "CONNECTED".equals(attempt.getResultCode())).count();
        response.setAttemptCount(attempts.size());
        response.setAnsweredCount(answered);
        response.setConnectedCount(connected);
        response.setCustomerCount(attempts.stream().map(OutboundAttempt::getMemberId).distinct().count());
        response.setTotalDurationSeconds(sum(attempts, OutboundAttempt::getDurationSeconds));
        response.setBillableSeconds(sum(attempts, OutboundAttempt::getBillableSeconds));
        response.setAnswerRate(rate(answered, attempts.size()));
        response.setConnectionRate(rate(connected, attempts.size()));
    }

    private void applyMetrics(OutboundDailyTrendResponse response, List<OutboundAttempt> attempts) {
        long answered = attempts.stream().filter(attempt -> attempt.getAnsweredAt() != null).count();
        long connected = attempts.stream().filter(attempt -> "CONNECTED".equals(attempt.getResultCode())).count();
        response.setAttemptCount(attempts.size());
        response.setAnsweredCount(answered);
        response.setConnectedCount(connected);
        response.setCustomerCount(attempts.stream().map(OutboundAttempt::getMemberId).distinct().count());
        response.setTotalDurationSeconds(sum(attempts, OutboundAttempt::getDurationSeconds));
        response.setBillableSeconds(sum(attempts, OutboundAttempt::getBillableSeconds));
        response.setAnswerRate(rate(answered, attempts.size()));
        response.setConnectionRate(rate(connected, attempts.size()));
    }

    private long sum(List<OutboundAttempt> attempts, Function<OutboundAttempt, Integer> getter) {
        return attempts.stream().map(getter).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0D : Math.round(numerator * 10000D / denominator) / 100D;
    }

    private void requireTask(Long taskId) {
        if (taskId == null || taskMapper.selectById(taskId) == null) {
            throw new ServiceException("外呼任务不存在");
        }
    }

    private String statusLabel(String status) {
        if (status == null) return null;
        return switch (status) {
            case "DIALING" -> "拨打中";
            case "ANSWERED" -> "已接听";
            case "ENDED" -> "已结束";
            default -> status;
        };
    }
}
