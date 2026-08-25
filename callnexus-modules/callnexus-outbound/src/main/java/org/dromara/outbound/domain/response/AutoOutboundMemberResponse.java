package org.dromara.outbound.domain.response;

import lombok.Data;

import java.util.Date;
import java.time.LocalDateTime;

@Data
public class AutoOutboundMemberResponse {
    private Long id;
    private Long customerId;
    private String customerName;
    private String phoneNumber;
    private Long customerPhoneId;
    private String phoneLabel;
    private Long sourceId;
    private Long sourceImportTaskId;
    private Long sourceImportBatchId;
    private String status;
    private Integer attemptCount;
    private String blockedReason;
    private String lastResultCode;
    private String lastResultLabel;
    private String lastResultRemark;
    private String failureCategory;
    private String failureCategoryLabel;
    private Boolean retryable;
    private LocalDateTime lastAttemptAt;
    private Date createTime;
}
