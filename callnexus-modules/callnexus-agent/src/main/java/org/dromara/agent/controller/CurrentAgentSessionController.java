package org.dromara.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.request.ChangeAgentStatusRequest;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/agent-session")
@RequiredArgsConstructor
public class CurrentAgentSessionController {
    private final CurrentAgentSessionService sessionService;

    @GetMapping("/me")
    public R<CurrentAgentResponse> current() {
        return R.ok(sessionService.current());
    }

    @PutMapping("/sign-in")
    public R<CurrentAgentResponse> signIn() {
        return R.ok(sessionService.signIn());
    }

    @PutMapping("/status")
    public R<CurrentAgentResponse> changeStatus(@Valid @RequestBody ChangeAgentStatusRequest request) {
        return R.ok(sessionService.changeStatus(request.getStatus()));
    }

    @DeleteMapping("/sign-out")
    public R<Void> signOut() {
        sessionService.signOut();
        return R.ok();
    }
}
