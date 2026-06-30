package org.dromara.call.domain.response;

import lombok.Data;

@Data
public class DispatchExtensionStatusResponse {
    private Long sipAccountId;
    private Long nodeId;
    private String nodeName;
    private String extension;
    private String displayName;
    private String domain;
    private Boolean enabled;
    private String registrationStatus;
    private Long agentId;
    private String agentName;
    private String agentPresenceStatus;
    private String callStatus;
    private String businessCallId;
}
