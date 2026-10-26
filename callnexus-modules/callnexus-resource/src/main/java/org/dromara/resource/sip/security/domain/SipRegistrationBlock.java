package org.dromara.resource.sip.security.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_sip_registration_block")
public class SipRegistrationBlock extends TenantEntity {
    @TableId
    private Long id;
    private Long nodeId;
    private Long sipAccountId;
    private String sourceIp;
    private String blockType;
    private String status;
    private Integer triggerCount;
    private LocalDateTime blockedAt;
    private LocalDateTime expiresAt;
    private String reasonCode;
    private String reasonDetail;
    private Long releasedBy;
    private LocalDateTime releasedAt;
    private String releaseReason;
    private String executionStatus;
}
