package org.dromara.report.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SatisfactionDetailResponse {
    private Long id;
    private Long sessionId;
    private String businessCallId;
    private LocalDateTime evaluatedAt;
    private LocalDateTime callStartedAt;
    private Long queueId;
    private String queueName;
    private String skillGroupName;
    private Long agentId;
    private String agentName;
    private String agentExtension;
    private String customerNumber;
    private Integer score;
    private String digit;
    private String status;
    private Long talkSeconds;
}
