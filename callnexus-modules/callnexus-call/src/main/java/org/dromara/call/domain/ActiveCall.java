package org.dromara.call.domain;

import lombok.Data;

import java.io.Serializable;

@Data
public class ActiveCall implements Serializable {
    private String callId;
    private Long agentId;
    private String agentExtension;
    private String destination;
    private Boolean external;
    private String gatewayCode;
    private String callerIdNumber;
}
