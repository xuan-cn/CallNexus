package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_template_item")
public class QualityTemplateItem extends TenantEntity {
    @TableId private Long id;
    private Long templateId;
    private Long templateVersionId;
    private String dimensionCode;
    private String dimensionName;
    private String itemCode;
    private String itemName;
    private String itemType;
    private Integer scoreValue;
    private Boolean fatalFlag;
    private Boolean allowNotApplicable;
    private Boolean evidenceRequired;
    private Boolean aiReviewEnabled;
    private BigDecimal aiConfidenceThreshold;
    private String aiPromptHint;
    private String ruleDescription;
    private Integer sortOrder;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
