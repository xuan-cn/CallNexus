package org.dromara.report.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.report.domain.request.ReportQuery;
import org.dromara.report.domain.response.AgentReportResponse;
import org.dromara.report.domain.response.CallDetailResponse;
import org.dromara.report.domain.response.CallDistributionResponse;
import org.dromara.report.domain.response.OverviewKpiResponse;
import org.dromara.report.domain.response.QueueReportResponse;
import org.dromara.report.domain.response.ReportTrendPointResponse;
import org.dromara.report.mapper.ReportMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReportService {
    private final ReportMapper mapper;

    public OverviewKpiResponse overview(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectOverview(TenantHelper.getTenantId(), range.startAt(), range.endAt(), LocalDateTime.now());
    }

    public List<ReportTrendPointResponse> trend(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        String granularity = granularity(query, range);
        List<ReportTrendPointResponse> rows = mapper.selectTrend(
            TenantHelper.getTenantId(), range.startAt(), range.endAt(), granularity);
        return fillTrend(rows, range, granularity);
    }

    public List<CallDistributionResponse> distribution(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectDistribution(TenantHelper.getTenantId(), range.startAt(), range.endAt(), query.getDirection());
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

    public List<QueueReportResponse> queues(ReportQuery query) {
        ReportRangeResolver.ReportRange range = range(query);
        return mapper.selectQueues(TenantHelper.getTenantId(), range.startAt(), range.endAt(),
            query.getQueueId(), query.getSkillGroupId(), query.getKeyword());
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
}
