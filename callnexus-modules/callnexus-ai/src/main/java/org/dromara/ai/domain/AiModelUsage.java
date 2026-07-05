package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_model_usage")
public class AiModelUsage extends TenantEntity {
    @TableId private Long id;
    private Long conversationId;
    private Long messageId;
    private Long providerId;
    private Long modelId;
    private String capability;
    private String requestId;
    private Integer inputTokens;
    private Integer outputTokens;
    private Long elapsedMs;
    private String status;
    private String errorCode;
    private String errorMessage;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
