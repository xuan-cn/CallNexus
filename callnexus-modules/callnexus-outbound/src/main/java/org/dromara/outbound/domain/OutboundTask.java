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
    private Long nodeId;
    private Long callerNumberId;
    private Long outboundLinePolicyId;
    private String dialMode;
    private String targetType;
    private Long targetId;
    private Long skillGroupId;
    private Integer concurrencyLimit;
    private Integer callsPerMinute;
    private Integer maxCallsPerDay;
    private Integer maxCallsTotal;
    private Integer minCallIntervalMinutes;
    private String scheduleTimezone;
    private Boolean resultWritebackEnabled;
    private String connectedTag;
    private String failedTag;
    private Boolean autoRetryEnabled;
    private Integer maxRetryCount;
    private Integer retryIntervalMinutes;
    private String retryResultCodes;
    private Boolean autoAssignDueRetry;
    private Long retryAssigneeAgentId;
    private LocalDateTime lastScheduledAt;
    private String lastScheduleSummary;
    private String schedulerOwner;
    private LocalDateTime schedulerLeaseUntil;
    private LocalDateTime schedulerHeartbeatAt;
    private Integer executionRound;
    private LocalDateTime executionStartedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
