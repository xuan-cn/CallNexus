package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_workflow")
public class AiWorkflow extends TenantEntity {
    @TableId private Long id;
    private String workflowCode;
    private String workflowName;
    private String sceneType;
    private String description;
    private Boolean enabled;
    private Long currentPublishedVersionId;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
