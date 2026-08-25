package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_auto_outbound_dispatch")
public class AutoOutboundDispatch extends TenantEntity {
    @TableId
    private Long id;
    private Long taskId;
    private Long memberId;
    private String dispatchKey;
    private Integer attemptNo;
    private String previousMemberStatus;
    private String status;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private Long attemptId;
    private String businessCallId;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime completedAt;
    private String hangupCause;
    private String failureReason;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
