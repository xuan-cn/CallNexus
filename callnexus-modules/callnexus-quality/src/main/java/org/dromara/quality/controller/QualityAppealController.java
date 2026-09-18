package org.dromara.quality.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.quality.domain.request.*;
import org.dromara.quality.domain.response.*;
import org.dromara.quality.service.QualityAppealService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/quality")
@RequiredArgsConstructor
public class QualityAppealController {
    private final QualityAppealService service;

    @GetMapping("/my-results")
    @SaCheckPermission("callcenter:quality-result:mine")
    public TableDataInfo<QualityMyResultResponse> myResults(QualityTaskPageQuery query, PageQuery pageQuery) {
        return service.myResults(query, pageQuery);
    }

    @GetMapping("/my-results/{taskId}")
    @SaCheckPermission("callcenter:quality-result:mine")
    public R<QualityTaskDetailResponse> myResult(@PathVariable Long taskId) { return R.ok(service.myResultDetail(taskId)); }

    @PostMapping("/tasks/{taskId}/appeals")
    @SaCheckPermission("callcenter:quality-appeal:create")
    public R<Long> create(@PathVariable Long taskId, @Valid @RequestBody QualityAppealCreateRequest request) {
        return R.ok(service.create(taskId, request));
    }

    @GetMapping("/appeals")
    @SaCheckPermission("callcenter:quality-appeal:list")
    public TableDataInfo<QualityAppealResponse> page(QualityAppealPageQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/appeals/{id}")
    @SaCheckPermission("callcenter:quality-appeal:query")
    public R<QualityAppealDetailResponse> get(@PathVariable Long id) { return R.ok(service.get(id)); }

    @GetMapping("/appeals/calibration")
    @SaCheckPermission("callcenter:quality-appeal:list")
    public R<List<QualityCalibrationResponse>> calibration() { return R.ok(service.calibration()); }

    @PostMapping("/appeals/{id}/reject")
    @SaCheckPermission("callcenter:quality-appeal:review")
    public R<Void> reject(@PathVariable Long id, @Valid @RequestBody QualityAppealDecisionRequest request) {
        service.reject(id, request); return R.ok();
    }

    @PostMapping("/appeals/{id}/recheck")
    @SaCheckPermission("callcenter:quality-appeal:review")
    public R<Void> recheck(@PathVariable Long id, @Valid @RequestBody QualityAppealDecisionRequest request) {
        service.recheck(id, request); return R.ok();
    }

    @PostMapping("/appeals/{id}/accept")
    @SaCheckPermission("callcenter:quality-appeal:review")
    public R<Long> accept(@PathVariable Long id, @Valid @RequestBody QualityAppealAcceptRequest request) {
        return R.ok(service.accept(id, request));
    }
}
