package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallRealtimeMessage {
    private String type;
    private String callId;
    private String businessCallId;
    private String legUuid;
    private String callerNumber;
    private String callerNumberType;
    private String callerMobileSegment;
    private String callerProvince;
    private String callerCity;
    private String callerCarrier;
    private String calledNumber;
    private String agentExtension;
    private String hangupCause;
    private LocalDateTime occurredAt;
}
