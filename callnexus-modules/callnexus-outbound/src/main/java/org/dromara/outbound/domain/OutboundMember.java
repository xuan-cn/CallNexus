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
    private String status;
    private Long claimedAgentId;
    private Long claimedUserId;
    private LocalDateTime claimedAt;
    private LocalDateTime leaseExpiresAt;
    private String businessCallId;
    private Integer attemptCount;
    private String resultCode;
    private String resultRemark;
    private LocalDateTime nextFollowUpAt;
    private LocalDateTime completedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
