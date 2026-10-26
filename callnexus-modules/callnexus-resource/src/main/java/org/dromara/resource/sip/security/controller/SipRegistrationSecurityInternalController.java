package org.dromara.resource.sip.security.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.freeswitch.config.FreeSwitchDirectoryProperties;
import org.dromara.resource.sip.security.domain.request.SipRegistrationEventRequest;
import org.dromara.resource.sip.security.domain.request.SipSecurityExecutionResultRequest;
import org.dromara.resource.sip.security.domain.response.SipSecurityExecutionTaskResponse;
import org.dromara.resource.sip.security.service.SipRegistrationSecurityManagementService;
import org.dromara.resource.sip.security.service.SipRegistrationSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/internal/freeswitch/sip-registration-security")
@RequiredArgsConstructor
public class SipRegistrationSecurityInternalController {
    private static final String TOKEN_HEADER = "X-CallNexus-FreeSWITCH-Token";

    private final FreeSwitchDirectoryProperties properties;
    private final SipRegistrationSecurityService securityService;
    private final SipRegistrationSecurityManagementService managementService;

    @PostMapping("/events")
    public R<Void> report(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                          @Valid @RequestBody SipRegistrationEventRequest request) {
        if (!validToken(token)) throw new InvalidNodeTokenException();
        securityService.record(request);
        return R.ok();
    }

    @PostMapping("/tasks/claim")
    public R<SipSecurityExecutionTaskResponse> claim(
        @RequestHeader("X-CallNexus-Node-Code") String nodeCode,
        @RequestHeader("X-CallNexus-Node-Token") String token) {
        return R.ok(managementService.claim(nodeCode, token));
    }

    @PostMapping("/tasks/{taskId}/result")
    public R<Void> reportResult(
        @PathVariable Long taskId,
        @RequestHeader("X-CallNexus-Node-Code") String nodeCode,
        @RequestHeader("X-CallNexus-Node-Token") String token,
        @Valid @RequestBody SipSecurityExecutionResultRequest request) {
        managementService.report(taskId, nodeCode, token, request);
        return R.ok();
    }

    private boolean validToken(String token) {
        String secret = properties.getSecret();
        if (StringUtils.isBlank(secret) || StringUtils.isBlank(token)) return false;
        return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    private static class InvalidNodeTokenException extends RuntimeException {
    }
}
