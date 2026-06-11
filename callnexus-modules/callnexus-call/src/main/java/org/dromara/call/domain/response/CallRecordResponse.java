package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallRecordResponse {
    private Long id;
    private Long nodeId;
    private String channelUuid;
    private String callUuid;
    private String direction;
    private String callerNumber;
    private String calledNumber;
    private Long agentId;
    private String agentExtension;
    private String callStatus;
    private LocalDateTime startedAt;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
}
