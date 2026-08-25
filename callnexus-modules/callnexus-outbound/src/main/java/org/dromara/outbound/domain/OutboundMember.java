package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_member")
public class OutboundMember extends TenantEntity {
    @TableId private Long id;
    private Long taskId;
    private Long customerId;
    private String customerName;
    private String phoneNumber;
    private String sourceType;
    private Long importBatchId;
    private Long sourceId;
    private Long sourceImportTaskId;
    private Long sourceImportBatchId;
    private Long customerPhoneId;
    private String phoneLabel;
    private Integer phonePriority;
    private String status;
    private Long claimedAgentId;
    private Long claimedUserId;
    private LocalDateTime claimedAt;
    private LocalDateTime leaseExpiresAt;
    private String scheduleKey;
    private LocalDateTime scheduledAt;
    private String businessCallId;
    private Integer attemptCount;
    private String resultCode;
    private String resultRemark;
    private LocalDateTime nextFollowUpAt;
    private LocalDateTime completedAt;
    private String completionReason;
    private String blockedReason;
    private LocalDateTime blockedAt;
    private Long blockedBlacklistId;
    private String statusBeforeBlocked;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
