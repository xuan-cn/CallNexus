package org.dromara.quality.service;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.quality.domain.request.QualityReportQuery;
import org.dromara.quality.domain.response.QualityOptionResponse;
import org.dromara.quality.domain.response.QualityAiAdoptionSummaryResponse;
import org.dromara.quality.domain.response.QualityAiAdoptionRankingResponse;
import org.dromara.quality.domain.response.QualityAiModificationResponse;
import org.dromara.quality.domain.response.QualityAppealRankingResponse;
import org.dromara.quality.domain.response.QualityAppealSummaryResponse;
import org.dromara.quality.domain.response.QualityDeductionItemResponse;
import org.dromara.quality.domain.response.QualityReportDetailResponse;
import org.dromara.quality.domain.response.QualityReportOptionsResponse;
import org.dromara.quality.domain.response.QualityReportRankingResponse;
import org.dromara.quality.domain.response.QualityReportSummaryResponse;
import org.dromara.quality.domain.response.QualityReportTrendResponse;
import org.dromara.quality.mapper.QualityReportMapper;
import org.dromara.quality.domain.vo.QualityDeductionItemExportVo;
import org.dromara.quality.domain.vo.QualityReportDetailExportVo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QualityReportService {
    private final QualityReportMapper mapper;
    private final QualityDataScopeService dataScopeService;

    public QualityReportSummaryResponse summary(QualityReportQuery query) {
        QueryContext context = context(query);
        if (context.emptyScope()) return emptySummary();
        QualityReportSummaryResponse result = mapper.selectSummary(context.tenantId(), context.range().startAt(),
            context.range().endAt(), query, context.scope());
        if (result == null) result = emptySummary();
        result.setEligibleCallCount(mapper.countEligibleCalls(context.tenantId(), context.range().startAt(),
            context.range().endAt(), query, context.scope()));
        completeSummary(result);
        return result;
    }

    public List<QualityReportTrendResponse> trend(QualityReportQuery query) {
        QueryContext context = context(query);
        if (context.emptyScope()) return List.of();
        Map<String, QualityReportTrendResponse> rows = new LinkedHashMap<>();
        LocalDate day = context.range().startAt().toLocalDate();
        LocalDate end = context.range().endAt().toLocalDate();
        while (day.isBefore(end)) {
            QualityReportTrendResponse row = new QualityReportTrendResponse();
            row.setBucket(day.toString());
            row.setEligibleCallCount(0L);
            row.setReviewedCallCount(0L);
            row.setResultCount(0L);
            row.setQualifiedCount(0L);
            row.setAverageScore(BigDecimal.ZERO);
            rows.put(row.getBucket(), row);
            day = day.plusDays(1);
        }
        mapper.selectEligibleTrend(context.tenantId(), context.range().startAt(), context.range().endAt(), query,
            context.scope()).forEach(source -> rows.get(source.getBucket()).setEligibleCallCount(source.getEligibleCallCount()));
        mapper.selectResultTrend(context.tenantId(), context.range().startAt(), context.range().endAt(), query,
            context.scope()).forEach(source -> {
                QualityReportTrendResponse target = rows.get(source.getBucket());
                if (target == null) return;
                target.setReviewedCallCount(source.getReviewedCallCount());
                target.setResultCount(source.getResultCount());
                target.setAverageScore(scale(source.getAverageScore()));
                target.setQualifiedCount(source.getQualifiedCount());
            });
        rows.values().forEach(row -> {
            row.setCoverageRate(rate(row.getReviewedCallCount(), row.getEligibleCallCount()));
            row.setQualifiedRate(rate(row.getQualifiedCount(), row.getResultCount()));
        });
        return new ArrayList<>(rows.values());
    }

    public List<QualityReportRankingResponse> agentRanking(QualityReportQuery query) {
        QueryContext context = context(query);
        if (context.emptyScope()) return List.of();
        List<QualityReportRankingResponse> rows = mapper.selectAgentRanking(context.tenantId(), context.range().startAt(),
            context.range().endAt(), query, context.scope());
        completeRankings(rows);
        return rows;
    }

    public List<QualityReportRankingResponse> skillGroupRanking(QualityReportQuery query) {
        QueryContext context = context(query);
        if (context.emptyScope()) return List.of();
        List<QualityReportRankingResponse> rows = mapper.selectSkillGroupRanking(context.tenantId(),
            context.range().startAt(), context.range().endAt(), query, context.scope());
        completeRankings(rows);
        return rows;
    }

    public List<QualityDeductionItemResponse> deductionItems(QualityReportQuery query) {
        QueryContext context = context(query);
        if (context.emptyScope()) return List.of();
        return mapper.selectDeductionItems(context.tenantId(), context.range().startAt(), context.range().endAt(),
            query, context.scope());
    }

    public TableDataInfo<QualityReportDetailResponse> details(QualityReportQuery query, PageQuery pageQuery) {
        QueryContext context = context(query);
        if (context.emptyScope()) return new TableDataInfo<>(List.of(), 0);
        Page<QualityReportDetailResponse> page = mapper.selectDetailPage(pageQuery.build(), context.tenantId(),
            context.range().startAt(), context.range().endAt(), query, context.scope());
        return new TableDataInfo<>(page.getRecords(), page.getTotal());
    }

    public QualityAiAdoptionSummaryResponse aiAdoption(QualityReportQuery query) {
        QueryContext context = context(query);
        QualityAiAdoptionSummaryResponse result = context.emptyScope() ? null : mapper.selectAiAdoptionSummary(
            context.tenantId(), context.range().startAt(), context.range().endAt(), query, context.scope());
        if (result == null) result = new QualityAiAdoptionSummaryResponse();
        result.setAiTaskCount(orZero(result.getAiTaskCount()));
        result.setEvaluatedItemCount(orZero(result.getEvaluatedItemCount()));
        result.setAdoptedItemCount(orZero(result.getAdoptedItemCount()));
        result.setModifiedItemCount(orZero(result.getModifiedItemCount()));
        result.setAdoptionRate(rate(result.getAdoptedItemCount(), result.getEvaluatedItemCount()));
        return result;
    }

    public List<QualityAiModificationResponse> aiModifications(QualityReportQuery query) {
        QueryContext context = context(query);
        return context.emptyScope() ? List.of() : mapper.selectAiModifications(context.tenantId(),
            context.range().startAt(), context.range().endAt(), query, context.scope());
    }

    public List<QualityAiAdoptionRankingResponse> aiAdoptionRanking(QualityReportQuery query, String dimension) {
        QueryContext context = context(query);
        if (context.emptyScope()) return List.of();
        String normalizedDimension = switch (dimension) {
            case "REVIEWER", "TEMPLATE" -> dimension;
            default -> "AGENT";
        };
        List<QualityAiAdoptionRankingResponse> rows = mapper.selectAiAdoptionRanking(context.tenantId(),
            context.range().startAt(), context.range().endAt(), query, context.scope(), normalizedDimension);
        rows.forEach(row -> row.setAdoptionRate(rate(row.getAdoptedItemCount(), row.getEvaluatedItemCount())));
        return rows;
    }

    public QualityAppealSummaryResponse appealSummary(QualityReportQuery query) {
        QueryContext context = context(query);
        QualityAppealSummaryResponse result = context.emptyScope() ? null : mapper.selectAppealSummary(
            context.tenantId(), context.range().startAt(), context.range().endAt(), query, context.scope());
        if (result == null) result = new QualityAppealSummaryResponse();
        result.setPublishedTaskCount(orZero(result.getPublishedTaskCount()));
        result.setAppealCount(orZero(result.getAppealCount()));
        result.setAppealedTaskCount(orZero(result.getAppealedTaskCount()));
        result.setPendingCount(orZero(result.getPendingCount()));
        result.setMaintainedCount(orZero(result.getMaintainedCount()));
        result.setAdjustedCount(orZero(result.getAdjustedCount()));
        result.setRecheckedCount(orZero(result.getRecheckedCount()));
        result.setAppealRate(rate(result.getAppealedTaskCount(), result.getPublishedTaskCount()));
        result.setSuccessRate(rate(result.getAdjustedCount() + result.getRecheckedCount(),
            result.getMaintainedCount() + result.getAdjustedCount() + result.getRecheckedCount()));
        return result;
    }

    public List<QualityAppealRankingResponse> appealRanking(QualityReportQuery query, String dimension) {
        QueryContext context = context(query);
        if (context.emptyScope()) return List.of();
        List<QualityAppealRankingResponse> rows = switch (dimension) {
            case "REVIEWER" -> mapper.selectReviewerAppealRanking(context.tenantId(), context.range().startAt(),
                context.range().endAt(), query, context.scope());
            case "SKILL_GROUP" -> mapper.selectSkillGroupAppealRanking(context.tenantId(), context.range().startAt(),
                context.range().endAt(), query, context.scope());
            default -> mapper.selectAgentAppealRanking(context.tenantId(), context.range().startAt(),
                context.range().endAt(), query, context.scope());
        };
        rows.forEach(row -> {
            row.setAppealRate(rate(row.getAppealedCount(), row.getPublishedCount()));
            row.setSuccessRate(rate(row.getAcceptedCount(), row.getCompletedCount()));
        });
        return rows;
    }

    public void exportDetails(QualityReportQuery query, HttpServletResponse response) {
        QueryContext context = context(query);
        List<QualityReportDetailResponse> details = context.emptyScope() ? List.of() : mapper.selectDetailPage(
            new Page<>(1, 100_000, false), context.tenantId(), context.range().startAt(), context.range().endAt(),
            query, context.scope()).getRecords();
        List<QualityReportDetailExportVo> rows = details.stream().map(this::detailExportRow).toList();
        ExcelUtil.exportExcel(rows, "质检明细", QualityReportDetailExportVo.class, response);
    }

    public void exportDeductionItems(QualityReportQuery query, HttpServletResponse response) {
        List<QualityDeductionItemExportVo> rows = deductionItems(query).stream().map(item -> {
            QualityDeductionItemExportVo row = new QualityDeductionItemExportVo();
            row.setItemCode(item.getItemCode());
            row.setItemName(item.getItemName());
            row.setOccurrenceCount(item.getOccurrenceCount());
            row.setAffectedCallCount(item.getAffectedCallCount());
            row.setTotalDeduction(item.getTotalDeduction());
            return row;
        }).toList();
        ExcelUtil.exportExcel(rows, "高频扣分项", QualityDeductionItemExportVo.class, response);
    }

    public QualityReportOptionsResponse options() {
        String tenantId = TenantHelper.getTenantId();
        QualityDataScopeService.Scope scope = dataScopeService.current();
        QualityReportOptionsResponse response = new QualityReportOptionsResponse();
        if (isEmpty(scope)) {
            response.setAgents(List.of());
            response.setQueues(List.of());
            response.setSkillGroups(List.of());
            return response;
        }
        response.setAgents(scope.restricted() && scope.agentIds().isEmpty()
            ? List.of() : mapper.selectAgentOptions(tenantId, scope));
        response.setQueues(scope.restricted() && scope.queueIds().isEmpty()
            ? List.of() : mapper.selectQueueOptions(tenantId, scope));
        response.setSkillGroups(scope.restricted() && scope.queueIds().isEmpty()
            ? List.of() : mapper.selectSkillGroupOptions(tenantId, scope));
        return response;
    }

    private QueryContext context(QualityReportQuery query) {
        return new QueryContext(TenantHelper.getTenantId(), QualityReportRangeResolver.resolve(query, LocalDate.now()),
            dataScopeService.current());
    }

    private void completeSummary(QualityReportSummaryResponse result) {
        result.setEligibleCallCount(orZero(result.getEligibleCallCount()));
        result.setReviewedCallCount(orZero(result.getReviewedCallCount()));
        result.setResultCount(orZero(result.getResultCount()));
        result.setQualifiedCount(orZero(result.getQualifiedCount()));
        result.setFatalCount(orZero(result.getFatalCount()));
        result.setAverageScore(scale(result.getAverageScore()));
        result.setCoverageRate(rate(result.getReviewedCallCount(), result.getEligibleCallCount()));
        result.setQualifiedRate(rate(result.getQualifiedCount(), result.getResultCount()));
        result.setFatalRate(rate(result.getFatalCount(), result.getResultCount()));
    }

    private void completeRankings(List<QualityReportRankingResponse> rows) {
        rows.forEach(row -> {
            row.setAverageScore(scale(row.getAverageScore()));
            row.setQualifiedRate(rate(row.getQualifiedCount(), row.getResultCount()));
        });
    }

    private QualityReportSummaryResponse emptySummary() {
        QualityReportSummaryResponse result = new QualityReportSummaryResponse();
        result.setEligibleCallCount(0L);
        result.setReviewedCallCount(0L);
        result.setResultCount(0L);
        result.setQualifiedCount(0L);
        result.setFatalCount(0L);
        completeSummary(result);
        return result;
    }

    private QualityReportDetailExportVo detailExportRow(QualityReportDetailResponse item) {
        QualityReportDetailExportVo row = new QualityReportDetailExportVo();
        row.setPublishedAt(item.getPublishedAt());
        row.setTaskCode(item.getTaskCode());
        row.setBusinessCallId(item.getBusinessCallId());
        row.setAgentName(item.getAgentName());
        row.setAgentExtension(item.getAgentExtension());
        row.setSkillGroupName(item.getSkillGroupName());
        row.setQueueName(item.getQueueName());
        row.setTemplateName(item.getTemplateName());
        row.setTemplateVersionNo(item.getTemplateVersionNo());
        row.setReviewerName(item.getReviewerName());
        row.setTotalScore(item.getTotalScore());
        row.setQualified(Boolean.TRUE.equals(item.getQualified()) ? "合格" : "不合格");
        row.setFatalFlag(Boolean.TRUE.equals(item.getFatalFlag()) ? "命中" : "未命中");
        row.setAppealStatus(appealStatusLabel(item.getAppealStatus()));
        row.setSummary(item.getSummary());
        return row;
    }

    private String appealStatusLabel(String status) {
        if (status == null) return "未申诉";
        return switch (status) {
            case "SUBMITTED" -> "待复核";
            case "REJECTED" -> "维持原结果";
            case "RECHECK_REQUESTED" -> "重新质检中";
            case "ACCEPTED" -> "申诉成功";
            default -> status;
        };
    }

    private boolean isEmpty(QualityDataScopeService.Scope scope) {
        return scope.restricted() && scope.agentIds().isEmpty() && scope.queueIds().isEmpty();
    }

    private long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(Long numerator, Long denominator) {
        long bottom = orZero(denominator);
        if (bottom == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(orZero(numerator)).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(bottom), 2, RoundingMode.HALF_UP);
    }

    private record QueryContext(String tenantId, QualityReportRangeResolver.Range range,
                                QualityDataScopeService.Scope scope) {
        private boolean emptyScope() {
            return scope.restricted() && scope.agentIds().isEmpty() && scope.queueIds().isEmpty();
        }
    }
}
