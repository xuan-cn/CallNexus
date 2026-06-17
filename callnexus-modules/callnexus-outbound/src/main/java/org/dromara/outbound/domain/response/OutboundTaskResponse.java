package org.dromara.outbound.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
public class OutboundTaskResponse {
    private Long id;
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
    private long totalCount;
    private long pendingCount;
    private long completedCount;
    private long dueRetryCount;
    private LocalDateTime lastScheduledAt;
    private String lastScheduleSummary;
    private Integer version;
    private Date createTime;
}
