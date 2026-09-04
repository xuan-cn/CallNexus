package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_workflow_version")
public class AiWorkflowVersion extends TenantEntity {
    @TableId private Long id;
    private Long workflowId;
    private Integer versionNo;
    private String versionName;
    private String status;
    private String definitionJson;
    private String definitionHash;
    private Long publishedBy;
    private LocalDateTime publishedAt;
}
