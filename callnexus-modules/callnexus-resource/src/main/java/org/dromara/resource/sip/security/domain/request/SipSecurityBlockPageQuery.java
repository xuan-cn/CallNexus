package org.dromara.resource.sip.security.domain.request;

import lombok.Data;

@Data
public class SipSecurityBlockPageQuery {
    private Long nodeId;
    private String blockType;
    private String sourceIp;
    private String status;
}
