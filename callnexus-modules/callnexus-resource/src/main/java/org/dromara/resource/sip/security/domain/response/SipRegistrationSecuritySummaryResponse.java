package org.dromara.resource.sip.security.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SipRegistrationSecuritySummaryResponse {
    private Long sipAccountId;
    private String status;
    private Integer recentFailureCount;
    private LocalDateTime lastFailureAt;
    private String lastFailureIp;
    private LocalDateTime lockedUntil;
    private String lockReason;
    private Long activeBlockId;
}
