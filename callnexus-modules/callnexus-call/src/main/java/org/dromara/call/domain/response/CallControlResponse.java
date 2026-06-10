package org.dromara.call.domain.response;

import lombok.Data;

@Data
public class CallControlResponse {
    private String callId;
    private String agentExtension;
    private String destination;
    private Boolean external;
    private String gatewayCode;
    private String callerIdNumber;
    private String status;
}
