package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_ticket_draft_task")
public class AiTicketDraftTask extends TenantEntity {
    @TableId private Long id;
    private Long policyId;
    private Long aiAgentId;
    private Long callSessionId;
    private String businessCallId;
    private String triggerType;
    private Boolean callCompleted;
    private Boolean transcriptReady;
    private Long transcriptId;
    private Long promptVersionId;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private String contextJson;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    @Version private Integer version;
}
