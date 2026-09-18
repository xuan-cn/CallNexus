package org.dromara.report.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.report.domain.request.ReportQuery;
import org.dromara.report.domain.response.AgentReportResponse;
import org.dromara.report.domain.response.AgentPresenceLogResponse;
import org.dromara.report.domain.response.CallDetailResponse;
import org.dromara.report.domain.response.CallDistributionResponse;
import org.dromara.report.domain.response.OverviewKpiResponse;
import org.dromara.report.domain.response.OutboundAttemptDetailResponse;
import org.dromara.report.domain.response.OutboundReportSummaryResponse;
import org.dromara.report.domain.response.OutboundTaskReportResponse;
import org.dromara.report.domain.response.OutboundTrendPointResponse;
import org.dromara.report.domain.response.QueueReportResponse;
import org.dromara.report.domain.response.ReportAgentOptionResponse;
import org.dromara.report.domain.response.ReportQueueOptionResponse;
import org.dromara.report.domain.response.ReportTrendPointResponse;
import org.dromara.report.domain.response.SatisfactionDetailResponse;
import org.dromara.report.domain.response.SatisfactionRankingResponse;
import org.dromara.report.domain.response.SatisfactionScoreDistributionResponse;
import org.dromara.report.domain.response.SatisfactionSummaryResponse;
import org.dromara.report.domain.response.SatisfactionTrendPointResponse;
import org.dromara.report.mapper.ReportMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReportService {
    private final ReportMapper mapper;

    public List<ReportAgentOptionResponse> agentOptions() {
        return mapper.selectAgentOptions(TenantHelper.getTenantId());
    }

    public List<ReportQueueOptionResponse> queueOptions() {
        return mapper.selectQueueOptions(TenantHelper.getTenantId());
    }

    public SatisfactionSummaryResponse satisfactionSummary(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectSatisfactionSummary(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getQueueId(), query.getAgentId(), query.getSkillGroupId(),
            query.getSatisfactionStatus(), query.getSatisfactionScore());
    }

    public List<SatisfactionTrendPointResponse> satisfactionTrend(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        String granularity = granularity(query, range);
        List<SatisfactionTrendPointResponse> rows = mapper.selectSatisfactionTrend(
            TenantHelper.getTenantId(), range.startAt(), range.endAt(), granularity,
            query.getQueueId(), query.getAgentId(), query.getSkillGroupId(),
            query.getSatisfactionStatus(), query.getSatisfactionScore());
        Map<String, SatisfactionTrendPointResponse> existing = new HashMap<>();
        rows.forEach(row -> existing.put(row.getBucket(), row));
        List<SatisfactionTrendPointResponse> result = new ArrayList<>();
        LocalDateTime cursor = range.startAt();
        while (cursor.isBefore(range.endAt())) {
            String bucket = "HOUR".equals(granularity)
                ? String.format("%s %02d:00", cursor.toLocalDate(), cursor.getHour())
                : cursor.toLocalDate().toString();
            result.add(existing.getOrDefault(bucket, emptySatisfactionTrend(bucket)));
            cursor = "HOUR".equals(granularity) ? cursor.plusHours(1) : cursor.plusDays(1);
        }
        return result;
    }

    public List<SatisfactionScoreDistributionResponse> satisfactionDistribution(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectSatisfactionDistribution(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getQueueId(), query.getAgentId(), query.getSkillGroupId(),
            query.getSatisfactionStatus(), query.getSatisfactionScore());
    }

    public List<SatisfactionRankingResponse> satisfactionQueueRanking(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectSatisfactionQueueRanking(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getQueueId(), query.getAgentId(), query.getSkillGroupId(),
            query.getSatisfactionStatus(), query.getSatisfactionScore());
    }

    public List<SatisfactionRankingResponse> satisfactionAgentRanking(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectSatisfactionAgentRanking(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getQueueId(), query.getAgentId(), query.getSkillGroupId(),
            query.getSatisfactionStatus(), query.getSatisfactionScore());
    }

    public TableDataInfo<SatisfactionDetailResponse> satisfactionDetails(ReportQuery query, PageQuery pageQuery) {
        ReportRangeResolver.ReportRange range = range(query);
        Page<SatisfactionDetailResponse> page = mapper.selectSatisfactionDetails(
            pageQuery.build(), TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getQueueId(), query.getAgentId(), query.getSkillGroupId(),
            query.getSatisfactionStatus(), query.getSatisfactionScore());
        return TableDataInfo.build(page);
    }

    public OverviewKpiResponse overview(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectOverview(TenantHelper.getTenantId(), range.startAt(), range.endAt(), LocalDateTime.now(),
            query.getDirection(), query.getAgentId(), query.getKeyword());
    }

    public List<ReportTrendPointResponse> trend(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        String granularity = granularity(query, range);
        List<ReportTrendPointResponse> rows = mapper.selectTrend(
            TenantHelper.getTenantId(), range.startAt(), range.endAt(), granularity,
            query.getDirection(), query.getAgentId(), query.getKeyword());
        return fillTrend(rows, range, granularity);
    }

    public List<CallDistributionResponse> distribution(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectDistribution(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getDirection(), query.getAgentId(), query.getKeyword());
    }

    public TableDataInfo<CallDetailResponse> callDetails(ReportQuery query, PageQuery pageQuery) {
        ReportRangeResolver.ReportRange range = range(query);
        Page<CallDetailResponse> page = mapper.selectCallDetails(
            pageQuery.build(), TenantHelper.getTenantId(), range.startAt(), range.endAt(), LocalDateTime.now(),
            query.getDirection(), query.getAnswerResult(), query.getAgentId(), query.getQueueId(), query.getKeyword());
        return TableDataInfo.build(page);
    }

    public List<AgentReportResponse> agents(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectAgents(TenantHelper.getTenantId(), range.startAt(), range.endAt(), LocalDateTime.now(),
            query.getAgentId(), query.getSkillGroupId(), query.getKeyword());
    }

    public TableDataInfo<AgentPresenceLogResponse> agentPresenceLogs(Long agentId,
                                                                      ReportQuery query,
                                                                      PageQuery pageQuery) {
        ReportRangeResolver.ReportRange range = range(query);
        Page<AgentPresenceLogResponse> page = mapper.selectAgentPresenceLogs(
            pageQuery.build(), TenantHelper.getTenantId(), agentId, range.startAt(), range.endAt(), LocalDateTime.now());
        return TableDataInfo.build(page);
    }

    public TableDataInfo<CallDetailResponse> agentCalls(Long agentId, ReportQuery query, PageQuery pageQuery) {
        ReportRangeResolver.ReportRange range = range(query);
        Page<CallDetailResponse> page = mapper.selectAgentCalls(
            pageQuery.build(), TenantHelper.getTenantId(), agentId, range.startAt(), range.endAt(), LocalDateTime.now());
        return TableDataInfo.build(page);
    }

    public List<QueueReportResponse> queues(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectQueues(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getQueueId(), query.getSkillGroupId(), query.getKeyword());
    }

    public OutboundReportSummaryResponse outboundSummary(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        String tenantId = TenantHelper.getTenantId();
        OutboundReportSummaryResponse response = mapper.selectOutboundSummary(
            tenantId, range.startAt(), range.endAt(), query.getTaskId(), query.getTaskType(),
            query.getResultCode(), query.getKeyword());
        long memberCount = defaultLong(mapper.selectOutboundMemberCount(
            tenantId, query.getTaskId(), query.getTaskType(), query.getKeyword()));
        response.setMemberCount(memberCount);
        response.setUndialedMemberCount(defaultLong(mapper.selectOutboundUndialedMemberCount(
            tenantId, query.getTaskId(), query.getTaskType(), query.getKeyword())));
        return response;
    }

    public List<OutboundTrendPointResponse> outboundTrend(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        String granularity = granularity(query, range);
        List<OutboundTrendPointResponse> rows = mapper.selectOutboundTrend(
            TenantHelper.getTenantId(), range.startAt(), range.endAt(), granularity,
            query.getTaskId(), query.getTaskType(), query.getResultCode(), query.getKeyword());
        return fillOutboundTrend(rows, range, granularity);
    }

    public List<CallDistributionResponse> outboundDistribution(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectOutboundDistribution(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getTaskId(), query.getTaskType(), query.getResultCode(), query.getKeyword());
    }

    public List<OutboundTaskReportResponse> outboundTasks(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectOutboundTasks(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getTaskId(), query.getTaskType(), query.getKeyword());
    }

    public TableDataInfo<OutboundAttemptDetailResponse> outboundAttempts(ReportQuery query, PageQuery pageQuery) {
        ReportRangeResolver.ReportRange range = range(query);
        Page<OutboundAttemptDetailResponse> page = mapper.selectOutboundAttempts(
            pageQuery.build(), TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getTaskId(), query.getTaskType(), query.getResultCode(), query.getKeyword());
        return TableDataInfo.build(page);
    }

    private ReportRangeResolver.ReportRange range(ReportQuery query) {
        return ReportRangeResolver.resolve(query, java.time.LocalDate.now());
    }

    private String granularity(ReportQuery query, ReportRangeResolver.ReportRange range) {
        return ReportRangeResolver.granularity(query, range);
    }

    private List<ReportTrendPointResponse> fillTrend(List<ReportTrendPointResponse> rows,
                                                      ReportRangeResolver.ReportRange range,
                                                      String granularity) {
        Map<String, ReportTrendPointResponse> existing = new HashMap<>();
        rows.forEach(row -> existing.put(row.getBucket(), row));
        List<ReportTrendPointResponse> result = new ArrayList<>();
        LocalDateTime cursor = range.startAt();
        while (cursor.isBefore(range.endAt())) {
            String bucket = "HOUR".equals(granularity)
                ? String.format("%s %02d:00", cursor.toLocalDate(), cursor.getHour())
                : cursor.toLocalDate().toString();
            result.add(existing.getOrDefault(bucket, emptyTrend(bucket)));
            cursor = "HOUR".equals(granularity) ? cursor.plusHours(1) : cursor.plusDays(1);
        }
        return result;
    }

    private ReportTrendPointResponse emptyTrend(String bucket) {
        ReportTrendPointResponse point = new ReportTrendPointResponse();
        point.setBucket(bucket);
        point.setTotalCalls(0L);
        point.setInboundCalls(0L);
        point.setOutboundCalls(0L);
        point.setAnsweredCalls(0L);
        return point;
    }

    private List<OutboundTrendPointResponse> fillOutboundTrend(List<OutboundTrendPointResponse> rows,
                                                                ReportRangeResolver.ReportRange range,
                                                                String granularity) {
        Map<String, OutboundTrendPointResponse> existing = new HashMap<>();
        rows.forEach(row -> existing.put(row.getBucket(), row));
        List<OutboundTrendPointResponse> result = new ArrayList<>();
        LocalDateTime cursor = range.startAt();
        while (cursor.isBefore(range.endAt())) {
            String bucket = "HOUR".equals(granularity)
                ? String.format("%s %02d:00", cursor.toLocalDate(), cursor.getHour())
                : cursor.toLocalDate().toString();
            result.add(existing.getOrDefault(bucket, emptyOutboundTrend(bucket)));
            cursor = "HOUR".equals(granularity) ? cursor.plusHours(1) : cursor.plusDays(1);
        }
        return result;
    }

    private OutboundTrendPointResponse emptyOutboundTrend(String bucket) {
        OutboundTrendPointResponse point = new OutboundTrendPointResponse();
        point.setBucket(bucket);
        point.setAttemptCount(0L);
        point.setAnsweredCount(0L);
        point.setBusinessSuccessCount(0L);
        return point;
    }

    private SatisfactionTrendPointResponse emptySatisfactionTrend(String bucket) {
        SatisfactionTrendPointResponse point = new SatisfactionTrendPointResponse();
        point.setBucket(bucket);
        point.setInvitationCount(0L);
        point.setSubmittedCount(0L);
        point.setSatisfiedCount(0L);
        point.setAverageScore(BigDecimal.ZERO);
        point.setParticipationRate(BigDecimal.ZERO);
        point.setSatisfactionRate(BigDecimal.ZERO);
        return point;
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
