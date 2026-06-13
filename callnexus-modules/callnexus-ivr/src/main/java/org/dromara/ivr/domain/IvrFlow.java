package org.dromara.ivr.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ivr_flow")
public class IvrFlow extends TenantEntity {
    @TableId private Long id;
    private String flowCode;
    private String flowName;
    private Long nodeGroupId;
    private String draftGraphJson;
    private Integer latestVersionNo;
    private String publishStatus;
    private Boolean enabled;
    private String remark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
