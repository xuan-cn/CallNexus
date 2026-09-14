package org.dromara.report.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboundAttemptDetailResponse {
    private Long id;
    private Long taskId;
    private String taskName;
    private String taskType;
    private String customerName;
    private String phoneNumber;
    private String agentName;
    private Integer attemptNo;
    private String status;
    private String resultCode;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
    private String failureCategory;
}
