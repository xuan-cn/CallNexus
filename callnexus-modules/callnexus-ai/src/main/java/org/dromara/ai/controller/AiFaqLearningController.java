package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.service.AiFaqLearningApplicationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/faq-learning-candidates")
@RequiredArgsConstructor
public class AiFaqLearningController {
    private final AiFaqLearningApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-faq-learning:list")
    public TableDataInfo<AiFaqLearningCandidateResponse> page(AiFaqLearningQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/statistics")
    @SaCheckPermission("callcenter:ai-faq-learning:list")
    public R<AiFaqLearningStatisticsResponse> statistics() {
        return R.ok(service.statistics());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:ai-faq-learning:query")
    public R<AiFaqLearningCandidateResponse> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{id}/approve")
    @SaCheckPermission("callcenter:ai-faq-learning:approve")
    public R<Void> approve(@PathVariable Long id, @Valid @RequestBody AiFaqLearningApproveRequest request) {
        service.approve(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/merge")
    @SaCheckPermission("callcenter:ai-faq-learning:merge")
    public R<Void> merge(@PathVariable Long id, @Valid @RequestBody AiFaqLearningMergeRequest request) {
        service.merge(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/reject")
    @SaCheckPermission("callcenter:ai-faq-learning:reject")
    public R<Void> reject(@PathVariable Long id, @Valid @RequestBody AiFaqLearningRejectRequest request) {
        service.reject(id, request);
        return R.ok();
    }

    @PostMapping("/{id}/reopen")
    @SaCheckPermission("callcenter:ai-faq-learning:review")
    public R<Void> reopen(@PathVariable Long id) {
        service.reopen(id);
        return R.ok();
    }

    @PostMapping("/batch-approve")
    @SaCheckPermission("callcenter:ai-faq-learning:approve")
    public R<AiFaqLearningBatchResponse> batchApprove(@Valid @RequestBody AiFaqLearningBatchRequest request) {
        return R.ok(service.batchApprove(request));
    }

    @PostMapping("/batch-merge")
    @SaCheckPermission("callcenter:ai-faq-learning:merge")
    public R<AiFaqLearningBatchResponse> batchMerge(@Valid @RequestBody AiFaqLearningBatchRequest request) {
        return R.ok(service.batchMerge(request));
    }

    @PostMapping("/batch-reject")
    @SaCheckPermission("callcenter:ai-faq-learning:reject")
    public R<AiFaqLearningBatchResponse> batchReject(@Valid @RequestBody AiFaqLearningBatchRequest request) {
        return R.ok(service.batchReject(request));
    }
}
