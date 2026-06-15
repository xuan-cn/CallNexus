package org.dromara.outbound.domain.response;

import lombok.Data;

@Data
public class OutboundBlacklistMatch {
    private Long blacklistId;
    private String scopeType;
    private Long taskId;
    private String normalizedPhone;
    private String reason;
}
