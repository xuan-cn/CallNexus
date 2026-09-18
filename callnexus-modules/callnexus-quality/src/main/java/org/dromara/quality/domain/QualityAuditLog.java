package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_audit_log")
public class QualityAuditLog extends TenantEntity {
    @TableId private Long id;
    private Long taskId;
    private String operationType;
    private String beforeJson;
    private String afterJson;
    private Long operatorId;
    private String operatorName;
    private LocalDateTime operationTime;
    private String remark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
