package org.dromara.ivr.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ivr_flow_version")
public class IvrFlowVersion extends TenantEntity {
    @TableId private Long id;
    private Long flowId;
    private Integer versionNo;
    private String graphJson;
    private String status;
    private LocalDateTime publishedAt;
    @TableLogic private Boolean deleted;
}
