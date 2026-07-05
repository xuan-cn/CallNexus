package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_message")
public class AiMessage extends TenantEntity {
    @TableId private Long id;
    private Long conversationId;
    private Long agentId;
    private String role;
    private String content;
    private String sourceType;
    private String status;
    private String requestId;
    private String failureReason;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
