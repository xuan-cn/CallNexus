package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallRealtimeMessage {
    private String type;
    private String callId;
    private String callerNumber;
    private String calledNumber;
    private String agentExtension;
    private String hangupCause;
    private LocalDateTime occurredAt;
}
