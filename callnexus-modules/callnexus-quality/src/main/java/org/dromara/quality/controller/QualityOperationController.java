package org.dromara.quality.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.quality.domain.QualityAlertRecord;
import org.dromara.quality.domain.QualityAlertRule;
import org.dromara.quality.domain.QualityReportSnapshot;
import org.dromara.quality.domain.request.QualityAlertHandleRequest;
import org.dromara.quality.domain.request.QualityAlertRuleRequest;
import org.dromara.quality.domain.request.QualityOperationQuery;
import org.dromara.quality.domain.request.QualityReportGenerateRequest;
import org.dromara.quality.service.QualityOperationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/quality/operations")
@RequiredArgsConstructor
public class QualityOperationController {
    private final QualityOperationService service;

    @GetMapping("/snapshots")
    @SaCheckPermission("callcenter:quality-operation:view")
    public TableDataInfo<QualityReportSnapshot> snapshots(QualityOperationQuery query, PageQuery pageQuery) {
        return service.snapshots(query, pageQuery);
    }

    @PostMapping("/snapshots/generate")
    @SaCheckPermission("callcenter:quality-operation:generate")
    public R<List<QualityReportSnapshot>> generate(@Valid @RequestBody QualityReportGenerateRequest request) {
        return R.ok(service.generate(request));
    }

    @GetMapping("/rules")
    @SaCheckPermission("callcenter:quality-operation:view")
    public TableDataInfo<QualityAlertRule> rules(QualityOperationQuery query, PageQuery pageQuery) {
        return service.rules(query, pageQuery);
    }

    @PostMapping("/rules")
    @SaCheckPermission("callcenter:quality-operation:config")
    public R<Long> createRule(@Valid @RequestBody QualityAlertRuleRequest request) {
        return R.ok(service.createRule(request));
    }

    @PutMapping("/rules/{id}")
    @SaCheckPermission("callcenter:quality-operation:config")
    public R<Void> updateRule(@PathVariable Long id, @Valid @RequestBody QualityAlertRuleRequest request) {
        service.updateRule(id, request);
        return R.ok();
    }

    @DeleteMapping("/rules/{id}")
    @SaCheckPermission("callcenter:quality-operation:config")
    public R<Void> deleteRule(@PathVariable Long id) {
        service.deleteRule(id);
        return R.ok();
    }

    @GetMapping("/alerts")
    @SaCheckPermission("callcenter:quality-operation:view")
    public TableDataInfo<QualityAlertRecord> alerts(QualityOperationQuery query, PageQuery pageQuery) {
        return service.alerts(query, pageQuery);
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @SaCheckPermission("callcenter:quality-operation:handle")
    public R<Void> acknowledge(@PathVariable Long id, @RequestBody QualityAlertHandleRequest request) {
        service.acknowledge(id, request);
        return R.ok();
    }

    @PostMapping("/alerts/{id}/close")
    @SaCheckPermission("callcenter:quality-operation:handle")
    public R<Void> close(@PathVariable Long id, @RequestBody QualityAlertHandleRequest request) {
        service.close(id, request);
        return R.ok();
    }
}
