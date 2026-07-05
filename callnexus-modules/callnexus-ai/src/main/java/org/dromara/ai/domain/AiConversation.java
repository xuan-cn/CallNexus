package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_conversation")
public class AiConversation extends TenantEntity {
    @TableId private Long id;
    private Long agentId;
    private Long userId;
    private String title;
    private String status;
    private LocalDateTime lastMessageAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
