package org.dromara.report.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
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
import org.dromara.report.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService service;

    @GetMapping("/agent-options")
    @SaCheckPermission(value = {"callcenter:report-call:view", "callcenter:report-agent:view",
        "callcenter:report-satisfaction:view"}, mode = SaMode.OR)
    public R<List<ReportAgentOptionResponse>> agentOptions() {
        return R.ok(service.agentOptions());
    }

    @GetMapping("/queue-options")
    @SaCheckPermission(value = {"callcenter:report-queue:view", "callcenter:report-satisfaction:view"}, mode = SaMode.OR)
    public R<List<ReportQueueOptionResponse>> queueOptions() {
        return R.ok(service.queueOptions());
    }

    @GetMapping("/satisfaction/summary")
    @SaCheckPermission("callcenter:report-satisfaction:view")
    public R<SatisfactionSummaryResponse> satisfactionSummary(ReportQuery query) {
        return R.ok(service.satisfactionSummary(query));
    }

    @GetMapping("/satisfaction/trend")
    @SaCheckPermission("callcenter:report-satisfaction:view")
    public R<List<SatisfactionTrendPointResponse>> satisfactionTrend(ReportQuery query) {
        return R.ok(service.satisfactionTrend(query));
    }

    @GetMapping("/satisfaction/distribution")
    @SaCheckPermission("callcenter:report-satisfaction:view")
    public R<List<SatisfactionScoreDistributionResponse>> satisfactionDistribution(ReportQuery query) {
        return R.ok(service.satisfactionDistribution(query));
    }

    @GetMapping("/satisfaction/queues")
    @SaCheckPermission("callcenter:report-satisfaction:view")
    public R<List<SatisfactionRankingResponse>> satisfactionQueues(ReportQuery query) {
        return R.ok(service.satisfactionQueueRanking(query));
    }

    @GetMapping("/satisfaction/agents")
    @SaCheckPermission("callcenter:report-satisfaction:view")
    public R<List<SatisfactionRankingResponse>> satisfactionAgents(ReportQuery query) {
        return R.ok(service.satisfactionAgentRanking(query));
    }

    @GetMapping("/satisfaction/details")
    @SaCheckPermission("callcenter:report-satisfaction:view")
    public TableDataInfo<SatisfactionDetailResponse> satisfactionDetails(ReportQuery query, PageQuery pageQuery) {
        return service.satisfactionDetails(query, pageQuery);
    }

    @GetMapping("/overview/kpis")
    @SaCheckPermission("callcenter:report-overview:view")
    public R<OverviewKpiResponse> overview(ReportQuery query) {
        return R.ok(service.overview(query));
    }

    @GetMapping("/overview/trend")
    @SaCheckPermission("callcenter:report-overview:view")
    public R<List<ReportTrendPointResponse>> overviewTrend(ReportQuery query) {
        return R.ok(service.trend(query));
    }

    @GetMapping("/overview/distribution")
    @SaCheckPermission("callcenter:report-overview:view")
    public R<List<CallDistributionResponse>> overviewDistribution(ReportQuery query) {
        return R.ok(service.distribution(query));
    }

    @GetMapping("/calls/summary")
    @SaCheckPermission("callcenter:report-call:view")
    public R<OverviewKpiResponse> callSummary(ReportQuery query) {
        return R.ok(service.overview(query));
    }

    @GetMapping("/calls/trend")
    @SaCheckPermission("callcenter:report-call:view")
    public R<List<ReportTrendPointResponse>> callTrend(ReportQuery query) {
        return R.ok(service.trend(query));
    }

    @GetMapping("/calls/distribution")
    @SaCheckPermission("callcenter:report-call:view")
    public R<List<CallDistributionResponse>> callDistribution(ReportQuery query) {
        return R.ok(service.distribution(query));
    }

    @GetMapping("/calls/details")
    @SaCheckPermission("callcenter:report-call:view")
    public TableDataInfo<CallDetailResponse> callDetails(ReportQuery query, PageQuery pageQuery) {
        return service.callDetails(query, pageQuery);
    }

    @GetMapping("/agents/summary")
    @SaCheckPermission("callcenter:report-agent:view")
    public R<List<AgentReportResponse>> agents(ReportQuery query) {
        return R.ok(service.agents(query));
    }

    @GetMapping("/agents/{agentId}/presence-logs")
    @SaCheckPermission("callcenter:report-agent:view")
    public TableDataInfo<AgentPresenceLogResponse> agentPresenceLogs(@PathVariable Long agentId,
                                                                     ReportQuery query,
                                                                     PageQuery pageQuery) {
        return service.agentPresenceLogs(agentId, query, pageQuery);
    }

    @GetMapping("/agents/{agentId}/calls")
    @SaCheckPermission("callcenter:report-agent:view")
    public TableDataInfo<CallDetailResponse> agentCalls(@PathVariable Long agentId,
                                                        ReportQuery query,
                                                        PageQuery pageQuery) {
        return service.agentCalls(agentId, query, pageQuery);
    }

    @GetMapping("/queues/summary")
    @SaCheckPermission("callcenter:report-queue:view")
    public R<List<QueueReportResponse>> queues(ReportQuery query) {
        return R.ok(service.queues(query));
    }

    @GetMapping("/outbound/summary")
    @SaCheckPermission("callcenter:report-outbound:view")
    public R<OutboundReportSummaryResponse> outboundSummary(ReportQuery query) {
        return R.ok(service.outboundSummary(query));
    }

    @GetMapping("/outbound/trend")
    @SaCheckPermission("callcenter:report-outbound:view")
    public R<List<OutboundTrendPointResponse>> outboundTrend(ReportQuery query) {
        return R.ok(service.outboundTrend(query));
    }

    @GetMapping("/outbound/distribution")
    @SaCheckPermission("callcenter:report-outbound:view")
    public R<List<CallDistributionResponse>> outboundDistribution(ReportQuery query) {
        return R.ok(service.outboundDistribution(query));
    }

    @GetMapping("/outbound/tasks")
    @SaCheckPermission("callcenter:report-outbound:view")
    public R<List<OutboundTaskReportResponse>> outboundTasks(ReportQuery query) {
        return R.ok(service.outboundTasks(query));
    }

    @GetMapping("/outbound/attempts")
    @SaCheckPermission("callcenter:report-outbound:view")
    public TableDataInfo<OutboundAttemptDetailResponse> outboundAttempts(ReportQuery query, PageQuery pageQuery) {
        return service.outboundAttempts(query, pageQuery);
    }
}
