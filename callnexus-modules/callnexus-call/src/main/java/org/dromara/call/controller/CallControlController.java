package org.dromara.call.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.request.OriginateCallRequest;
import org.dromara.call.domain.request.TransferCallRequest;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallControlController {
    private final CallControlApplicationService applicationService;

    @PostMapping
    public R<CallControlResponse> originate(@Valid @RequestBody OriginateCallRequest request) {
        return R.ok(applicationService.originate(request.getDestination()));
    }

    @DeleteMapping("/{callId}")
    public R<Void> hangup(@PathVariable String callId) {
        applicationService.hangup(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/hold")
    public R<Void> hold(@PathVariable String callId) {
        applicationService.hold(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/unhold")
    public R<Void> unhold(@PathVariable String callId) {
        applicationService.unhold(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/transfer")
    public R<Void> blindTransfer(@PathVariable String callId, @Valid @RequestBody TransferCallRequest request) {
        applicationService.blindTransfer(callId, request.getTargetExtension());
        return R.ok();
    }

    @PostMapping("/{callId}/consult-transfer")
    public R<CallControlResponse> startConsultTransfer(@PathVariable String callId, @Valid @RequestBody TransferCallRequest request) {
        return R.ok(applicationService.startConsultTransfer(callId, request.getTargetExtension()));
    }

    @PostMapping("/{callId}/consult-transfer/cancel")
    public R<Void> cancelConsultTransfer(@PathVariable String callId) {
        applicationService.cancelConsultTransfer(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/consult-transfer/complete")
    public R<Void> completeConsultTransfer(@PathVariable String callId) {
        applicationService.completeConsultTransfer(callId);
        return R.ok();
    }
}
