package org.dromara.resource.sip.domain.response;

import lombok.Data;

@Data
public class SipAccountRealtimeResponse {
    private Long sipAccountId;
    private String tenantId;
    private String extension;
}
