package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_model")
public class AiModel extends TenantEntity {
    @TableId private Long id;
    private Long providerId;
    private String modelCode;
    private String modelName;
    private String capability;
    private Integer vectorDimension;
    private Integer maxBatchSize;
    private Integer maxInputTokens;
    private Boolean defaultModel;
    private String requestOptionsJson;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
