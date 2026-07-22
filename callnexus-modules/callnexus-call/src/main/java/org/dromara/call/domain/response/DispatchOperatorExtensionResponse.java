package org.dromara.call.domain.response;

import lombok.Data;

@Data
public class DispatchOperatorExtensionResponse {
    private Boolean configured;
    private Long userId;
    private Long sipAccountId;
    private Long nodeId;
    private String nodeName;
    private String extension;
    private String authUsername;
    private String displayName;
    private String domain;
}
