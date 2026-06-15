package org.dromara.outbound.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class OutboundTaskResponse {
    private Long id;
    private String taskCode;
    private String taskName;
    private String taskType;
    private String status;
    private String description;
    private Boolean autoRetryEnabled;
    private Integer maxRetryCount;
    private Integer retryIntervalMinutes;
    private String retryResultCodes;
    private long totalCount;
    private long pendingCount;
    private long completedCount;
    private Integer version;
    private Date createTime;
}
