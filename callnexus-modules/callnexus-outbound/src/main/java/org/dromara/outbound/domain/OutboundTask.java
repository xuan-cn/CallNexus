package org.dromara.outbound.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

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
    private Long callerNumberId;
    private Boolean autoRetryEnabled;
    private Integer maxRetryCount;
    private Integer retryIntervalMinutes;
    private String retryResultCodes;
    private Boolean autoAssignDueRetry;
    private Long retryAssigneeAgentId;
    private LocalDateTime lastScheduledAt;
    private String lastScheduleSummary;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
