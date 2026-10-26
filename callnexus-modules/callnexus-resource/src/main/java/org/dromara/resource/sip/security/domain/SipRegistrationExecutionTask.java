package org.dromara.resource.sip.security.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_sip_registration_execution_task")
public class SipRegistrationExecutionTask extends TenantEntity {
    @TableId
    private Long id;
    private Long nodeId;
    private Long blockId;
    private String action;
    private String sourceIp;
    private LocalDateTime expiresAt;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private String failureReason;
    private LocalDateTime executedAt;
    private String idempotencyKey;
}
