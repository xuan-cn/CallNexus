package org.dromara.agent.domain.response;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class CallQueueResponse {
    private Long id;
    private String queueCode;
    private String queueName;
    private Long nodeGroupId;
    private String nodeGroupName;
    private List<Long> nodeIds;
    private Long skillGroupId;
    private String skillGroupName;
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
    private Integer version;
    private Date createTime;
}
