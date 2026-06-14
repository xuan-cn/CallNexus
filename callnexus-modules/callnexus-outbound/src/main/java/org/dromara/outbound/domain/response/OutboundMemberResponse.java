package org.dromara.outbound.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboundMemberResponse {
    private Long id;
    private Long taskId;
    private Long customerId;
    private String customerName;
    private String phoneNumber;
    private String status;
    private Long claimedAgentId;
    private LocalDateTime claimedAt;
    private LocalDateTime leaseExpiresAt;
    private String businessCallId;
    private Integer attemptCount;
    private String resultCode;
    private String resultRemark;
    private LocalDateTime nextFollowUpAt;
    private LocalDateTime completedAt;
}
