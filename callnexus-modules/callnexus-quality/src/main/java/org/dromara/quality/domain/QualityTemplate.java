package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_template")
public class QualityTemplate extends TenantEntity {
    @TableId private Long id;
    private String templateCode;
    private String templateName;
    private String directionScope;
    private Integer totalScore;
    private Integer qualifiedScore;
    private Boolean aiReviewEnabled;
    private String status;
    private Long currentVersionId;
    private String remark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
