package org.dromara.outbound.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CustomerDialAttemptResponse {
    private Long id;
    private String phoneNumber;
    private Integer attemptNo;
    private String businessCallId;
    private String status;
    private String resultCode;
    private String resultRemark;
    private String suggestedResultCode;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
}
