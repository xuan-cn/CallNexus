package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallDiagnosticLegResponse {
    private Long id;
    private String businessCallId;
    private Long nodeId;
    private String legUuid;
    private String legRole;
    private Long agentId;
    private String agentExtension;
    private String callerNumber;
    private String calledNumber;
    private String legState;
    private Boolean active;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime bridgedAt;
    private LocalDateTime heldAt;
    private LocalDateTime parkedAt;
    private LocalDateTime endedAt;
    private String hangupCause;
}
