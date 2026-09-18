package org.dromara.quality.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.quality.domain.request.QualityReportQuery;
import org.dromara.quality.domain.response.QualityReportOptionsResponse;
import org.dromara.quality.domain.response.QualityAiAdoptionSummaryResponse;
import org.dromara.quality.domain.response.QualityAiAdoptionRankingResponse;
import org.dromara.quality.domain.response.QualityAiModificationResponse;
import org.dromara.quality.domain.response.QualityAppealRankingResponse;
import org.dromara.quality.domain.response.QualityAppealSummaryResponse;
import org.dromara.quality.domain.response.QualityDeductionItemResponse;
import org.dromara.quality.domain.response.QualityReportDetailResponse;
import org.dromara.quality.domain.response.QualityReportRankingResponse;
import org.dromara.quality.domain.response.QualityReportSummaryResponse;
import org.dromara.quality.domain.response.QualityReportTrendResponse;
import org.dromara.quality.service.QualityReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/quality/reports")
@RequiredArgsConstructor
@SaCheckPermission("callcenter:report-quality:view")
public class QualityReportController {
    private final QualityReportService service;

    @GetMapping("/summary")
    public R<QualityReportSummaryResponse> summary(QualityReportQuery query) {
        return R.ok(service.summary(query));
    }

    @GetMapping("/trend")
    public R<List<QualityReportTrendResponse>> trend(QualityReportQuery query) {
        return R.ok(service.trend(query));
    }

    @GetMapping("/agent-ranking")
    public R<List<QualityReportRankingResponse>> agentRanking(QualityReportQuery query) {
        return R.ok(service.agentRanking(query));
    }

    @GetMapping("/skill-group-ranking")
    public R<List<QualityReportRankingResponse>> skillGroupRanking(QualityReportQuery query) {
        return R.ok(service.skillGroupRanking(query));
    }

    @GetMapping("/deduction-items")
    public R<List<QualityDeductionItemResponse>> deductionItems(QualityReportQuery query) {
        return R.ok(service.deductionItems(query));
    }

    @GetMapping("/details")
    public TableDataInfo<QualityReportDetailResponse> details(QualityReportQuery query, PageQuery pageQuery) {
        return service.details(query, pageQuery);
    }

    @GetMapping("/ai-adoption")
    public R<QualityAiAdoptionSummaryResponse> aiAdoption(QualityReportQuery query) {
        return R.ok(service.aiAdoption(query));
    }

    @GetMapping("/ai-modifications")
    public R<List<QualityAiModificationResponse>> aiModifications(QualityReportQuery query) {
        return R.ok(service.aiModifications(query));
    }

    @GetMapping("/ai-adoption-ranking")
    public R<List<QualityAiAdoptionRankingResponse>> aiAdoptionRanking(QualityReportQuery query,
                                                                       @RequestParam(defaultValue = "AGENT") String dimension) {
        return R.ok(service.aiAdoptionRanking(query, dimension));
    }

    @GetMapping("/appeal-summary")
    public R<QualityAppealSummaryResponse> appealSummary(QualityReportQuery query) {
        return R.ok(service.appealSummary(query));
    }

    @GetMapping("/appeal-ranking")
    public R<List<QualityAppealRankingResponse>> appealRanking(QualityReportQuery query,
                                                                @RequestParam(defaultValue = "AGENT") String dimension) {
        return R.ok(service.appealRanking(query, dimension));
    }

    @PostMapping("/details/export")
    public void exportDetails(QualityReportQuery query, HttpServletResponse response) {
        service.exportDetails(query, response);
    }

    @PostMapping("/deduction-items/export")
    public void exportDeductionItems(QualityReportQuery query, HttpServletResponse response) {
        service.exportDeductionItems(query, response);
    }

    @GetMapping("/options")
    public R<QualityReportOptionsResponse> options() {
        return R.ok(service.options());
    }
}
