package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.AiTicketDraftResponse;
import org.dromara.ai.domain.response.AiTicketDraftBatchReviewResponse;
import org.dromara.ai.service.AiTicketDraftReviewService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.*;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/ai-ticket-drafts")
@RequiredArgsConstructor
public class AiTicketDraftController {
    private final AiTicketDraftReviewService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-ticket-draft:list")
    public TableDataInfo<AiTicketDraftResponse> page(AiTicketDraftQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:ai-ticket-draft:query")
    public R<AiTicketDraftResponse> get(@PathVariable Long id) { return R.ok(service.get(id)); }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:ai-ticket-draft:edit")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AiTicketDraftUpdateRequest request) {
        service.update(id, request); return R.ok();
    }

    @PostMapping("/{id}/approve")
    @SaCheckPermission("callcenter:ai-ticket-draft:review")
    public R<Long> approve(@PathVariable Long id, @Valid @RequestBody AiTicketDraftReviewRequest request) {
        return R.ok(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @SaCheckPermission("callcenter:ai-ticket-draft:review")
    public R<Void> reject(@PathVariable Long id, @Valid @RequestBody AiTicketDraftReviewRequest request) {
        service.reject(id, request); return R.ok();
    }

    @PostMapping("/batch-approve")
    @SaCheckPermission("callcenter:ai-ticket-draft:review")
    public R<AiTicketDraftBatchReviewResponse> batchApprove(@Valid @RequestBody AiTicketDraftBatchReviewRequest request) {
        return R.ok(service.batchApprove(request));
    }

    @PostMapping("/batch-reject")
    @SaCheckPermission("callcenter:ai-ticket-draft:review")
    public R<AiTicketDraftBatchReviewResponse> batchReject(@Valid @RequestBody AiTicketDraftBatchReviewRequest request) {
        return R.ok(service.batchReject(request));
    }

    @PostMapping("/{id}/regenerate")
    @SaCheckPermission("callcenter:ai-ticket-draft:regenerate")
    public R<Void> regenerate(@PathVariable Long id, @Valid @RequestBody AiTicketDraftReviewRequest request) {
        service.regenerate(id, request); return R.ok();
    }
}
