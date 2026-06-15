package org.dromara.outbound.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboundAttemptResponse {
    private Long id;
    private Long taskId;
    private Long memberId;
    private Long customerId;
    private String taskName;
    private String customerName;
    private String phoneNumber;
    private Long agentId;
    private Long userId;
    private Integer attemptNo;
    private String businessCallId;
    private String status;
    private String resultCode;
    private String resultRemark;
    private String suggestedResultCode;
    private String suggestedResultLabel;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
    private String hangupCauseLabel;
}
