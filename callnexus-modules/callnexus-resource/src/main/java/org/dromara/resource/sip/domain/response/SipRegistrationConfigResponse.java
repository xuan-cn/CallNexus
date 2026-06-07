package org.dromara.resource.sip.domain.response;

import lombok.Data;

@Data
public class SipRegistrationConfigResponse {
    private Long sipAccountId;
    private Long nodeId;
    private String extension;
    private String sipDomain;
    private String wssUrl;
}
