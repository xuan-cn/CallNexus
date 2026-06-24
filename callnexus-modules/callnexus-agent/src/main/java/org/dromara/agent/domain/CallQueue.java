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
    private Long callerNumberId;
    private Boolean maskCallerNumber;
    private Boolean manualAnswer;
    private Boolean busyTransferMobile;
    private String busyTransferNumber;
    private Integer forceWaitSeconds;
    private Long forceWaitMediaId;
    private String answerAction;
    private Long answerMediaId;
    private String hangupKeyAction;
    private String timeoutAction;
    private String timeoutTarget;
    private String noAgentAction;
    private String noAgentTarget;
    private Integer noAgentWaitSeconds;
    private String agentNoAnswerAction;
    private Boolean agentTimeoutTransferMobile;
    private String agentTimeoutTransferNumber;
    private Boolean stickyAgentEnabled;
    private Boolean queueAnnounceEnabled;
    private Integer queueAnnounceInterval;
    private Long queueAnnounceMediaId;
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
