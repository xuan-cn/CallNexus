package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_result")
public class QualityResult extends TenantEntity {
    @TableId private Long id;
    private Long taskId;
    private Integer resultVersion;
    private Integer totalScore;
    private Boolean qualified;
    private Boolean fatalFlag;
    private String summary;
    private String improvementSuggestion;
    private String source;
    private Long aiModelId;
    private String rawResponseJson;
    private Boolean effectiveFlag;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
