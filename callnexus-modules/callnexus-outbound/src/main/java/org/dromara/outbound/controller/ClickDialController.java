package org.dromara.outbound.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.outbound.domain.request.ClickDialCustomerQuery;
import org.dromara.outbound.domain.request.ClickDialRequest;
import org.dromara.outbound.domain.request.CompleteClickDialRequest;
import org.dromara.outbound.domain.response.ClickDialCallResponse;
import org.dromara.outbound.domain.response.ClickDialCustomerResponse;
import org.dromara.outbound.domain.response.ClickDialSummaryResponse;
import org.dromara.outbound.domain.response.CustomerDialAttemptResponse;
import org.dromara.outbound.service.ClickDialService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/click-dial")
@RequiredArgsConstructor
public class ClickDialController {
    private final ClickDialService service;

    @GetMapping("/customers")
    @SaCheckPermission("callcenter:click-dial:list")
    public TableDataInfo<ClickDialCustomerResponse> page(ClickDialCustomerQuery query) {
        return service.page(query);
    }

    @GetMapping("/summary")
    @SaCheckPermission("callcenter:click-dial:list")
    public R<ClickDialSummaryResponse> summary() {
        return R.ok(service.summary());
    }

    @PostMapping("/assignments/{assignmentId}/dial")
    @SaCheckPermission("callcenter:click-dial:execute")
    public R<ClickDialCallResponse> dial(@PathVariable Long assignmentId,
                                         @RequestBody(required = false) ClickDialRequest request) {
        return R.ok(service.dial(assignmentId, request));
    }

    @PutMapping("/assignments/{assignmentId}/result")
    @SaCheckPermission("callcenter:click-dial:result")
    public R<Void> complete(@PathVariable Long assignmentId,
                            @Valid @RequestBody CompleteClickDialRequest request) {
        service.complete(assignmentId, request);
        return R.ok();
    }

    @GetMapping("/assignments/{assignmentId}/attempts")
    @SaCheckPermission("callcenter:click-dial:list")
    public R<List<CustomerDialAttemptResponse>> attempts(@PathVariable Long assignmentId) {
        return R.ok(service.attempts(assignmentId));
    }
}
