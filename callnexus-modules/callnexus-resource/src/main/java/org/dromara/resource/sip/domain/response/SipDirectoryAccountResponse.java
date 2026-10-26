package org.dromara.resource.sip.domain.response;

import lombok.Data;

@Data
public class SipDirectoryAccountResponse {
    private Long id;
    private Long nodeId;
    private String extension;
    private String authUsername;
    private String displayName;
    private String domain;
    private String authPassword;
}
