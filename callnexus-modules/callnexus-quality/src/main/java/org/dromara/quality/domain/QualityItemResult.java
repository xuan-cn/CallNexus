package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_item_result")
public class QualityItemResult extends TenantEntity {
    @TableId private Long id;
    private Long qualityResultId;
    private Long templateItemId;
    private String itemCode;
    private String itemName;
    private String result;
    private Integer scoreChange;
    private BigDecimal confidence;
    private String reason;
    private String evidenceJson;
    private Boolean manuallyModified;
    private String modificationReason;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
