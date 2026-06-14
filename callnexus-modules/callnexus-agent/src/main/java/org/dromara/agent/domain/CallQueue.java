package org.dromara.agent.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_call_queue")
public class CallQueue extends TenantEntity {
    @TableId private Long id;
    private String queueCode;
    private String queueName;
    private Long nodeGroupId;
    private Long skillGroupId;
    private String strategy;
    private Long waitMediaId;
    private Integer maxWaitSeconds;
    private Integer ringTimeoutSeconds;
    private Integer maxNoAnswer;
    private Integer wrapUpSeconds;
    private String syncStatus;
    private java.time.LocalDateTime lastSyncedAt;
    private String syncError;
    private Boolean enabled;
    private String remark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
