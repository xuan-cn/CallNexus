package org.dromara.agent.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallQueueRecentCallResponse {
    private Long sessionId;
    private String businessCallId;
    private String direction;
    private String callerNumber;
    private String calledNumber;
    private Long agentId;
    private String agentExtension;
    private String callStatus;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Long waitSeconds;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
    private String recordingStatus;
}
