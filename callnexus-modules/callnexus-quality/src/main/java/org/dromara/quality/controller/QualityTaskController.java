package org.dromara.quality.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.request.CallRecordPageQuery;
import org.dromara.call.domain.response.CallRecordResponse;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.quality.domain.request.*;
import org.dromara.quality.domain.response.*;
import org.dromara.quality.service.QualityTaskService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/quality/tasks")
@RequiredArgsConstructor
public class QualityTaskController {
    private final QualityTaskService service;

    @GetMapping @SaCheckPermission("callcenter:quality-task:list")
    public TableDataInfo<QualityTaskResponse> page(QualityTaskPageQuery query, PageQuery pageQuery) { return service.page(query, pageQuery); }
    @GetMapping("/call-candidates") @SaCheckPermission("callcenter:quality-task:create")
    public TableDataInfo<CallRecordResponse> candidates(CallRecordPageQuery query, PageQuery pageQuery) { return service.callCandidates(query, pageQuery); }
    @GetMapping("/reviewers") @SaCheckPermission("callcenter:quality-task:list")
    public R<List<QualityUserOptionResponse>> reviewers() { return R.ok(service.reviewerOptions()); }
    @GetMapping("/{id}") @SaCheckPermission("callcenter:quality-task:query")
    public R<QualityTaskDetailResponse> get(@PathVariable Long id) { return R.ok(service.get(id)); }
    @PostMapping("/manual") @SaCheckPermission("callcenter:quality-task:create")
    public R<List<Long>> createManual(@Valid @RequestBody ManualQualityTaskCreateRequest request) { return R.ok(service.createManual(request)); }
    @PostMapping("/{id}/assign") @SaCheckPermission("callcenter:quality-task:assign")
    public R<Void> assign(@PathVariable Long id, @Valid @RequestBody QualityTaskAssignRequest request) { service.assign(id, request); return R.ok(); }
    @PostMapping("/{id}/claim") @SaCheckPermission("callcenter:quality-task:claim")
    public R<Void> claim(@PathVariable Long id) { service.claim(id); return R.ok(); }
    @PostMapping("/{id}/ai-review") @SaCheckPermission("callcenter:quality-task:ai-review")
    public R<Long> aiReview(@PathVariable Long id) { return R.ok(service.triggerAiReview(id)); }
    @PostMapping("/{id}/submit") @SaCheckPermission("callcenter:quality-task:submit")
    public R<Long> submit(@PathVariable Long id, @Valid @RequestBody QualityReviewSubmitRequest request) { return R.ok(service.submit(id, request)); }
    @PostMapping("/{id}/publish") @SaCheckPermission("callcenter:quality-task:publish")
    public R<Void> publish(@PathVariable Long id) { service.publish(id); return R.ok(); }
}
