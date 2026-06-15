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
@TableName("cc_outbound_attempt")
public class OutboundAttempt extends TenantEntity {
    @TableId private Long id;
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
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
