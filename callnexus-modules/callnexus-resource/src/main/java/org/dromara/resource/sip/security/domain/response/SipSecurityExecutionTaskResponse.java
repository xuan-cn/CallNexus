package org.dromara.resource.sip.security.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SipSecurityExecutionTaskResponse {
    private Long taskId;
    private String leaseToken;
    private String action;
    private String sourceIp;
    private LocalDateTime expiresAt;
    private String idempotencyKey;
}
