package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_outbound_task")
public class OutboundTask extends TenantEntity {
    @TableId private Long id;
    private String taskCode;
    private String taskName;
    private String taskType;
    private String status;
    private String description;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
