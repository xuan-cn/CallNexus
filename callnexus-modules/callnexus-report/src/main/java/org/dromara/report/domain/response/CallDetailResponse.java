package org.dromara.report.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallDetailResponse {
    private Long id;
    private String businessCallId;
    private String direction;
    private String customerNumber;
    private String agentName;
    private String agentExtension;
    private String queueName;
    private String callStatus;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Long waitSeconds;
    private Long talkSeconds;
    private String hangupCause;
}
