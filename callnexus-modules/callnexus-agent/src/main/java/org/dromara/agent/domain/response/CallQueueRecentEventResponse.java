package org.dromara.agent.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallQueueRecentEventResponse {
    private Long eventId;
    private Long sessionId;
    private String eventType;
    private String eventText;
    private String callerNumber;
    private String calledNumber;
    private String agentExtension;
    private String hangupCause;
    private Long waitSeconds;
    private String fromTarget;
    private String toTarget;
    private LocalDateTime occurredAt;
    private String metadataJson;
}
