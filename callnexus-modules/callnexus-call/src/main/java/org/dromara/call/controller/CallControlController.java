package org.dromara.call.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.request.OriginateCallRequest;
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
}
