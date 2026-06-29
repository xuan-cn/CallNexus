package org.dromara.agent.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CallQueueRequest {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") private String queueCode;
    @NotBlank @Size(max = 64) private String queueName;
    @NotNull private Long nodeGroupId;
    @NotNull private Long skillGroupId;
    @NotBlank private String strategy;
    private Long waitMediaId;
    private Long callerNumberId;
    @NotNull private Boolean maskCallerNumber;
    @NotNull private Boolean manualAnswer;
    @NotNull private Boolean busyTransferMobile;
    @Size(max = 32) private String busyTransferNumber;
    @NotNull @Min(0) @Max(3600) private Integer forceWaitSeconds;
    private Long forceWaitMediaId;
    @NotBlank private String answerAction;
    private Long answerMediaId;
    @NotBlank private String hangupKeyAction;
    @NotNull private Boolean satisfactionEnabled;
    private Long satisfactionMediaId;
    @NotNull @Min(3) @Max(60) private Integer satisfactionTimeoutSeconds;
    @NotBlank private String timeoutAction;
    @Size(max = 64) private String timeoutTarget;
    @NotBlank private String noAgentAction;
    @Size(max = 64) private String noAgentTarget;
    @NotNull @Min(0) @Max(3600) private Integer noAgentWaitSeconds;
    @NotBlank private String agentNoAnswerAction;
    @NotNull private Boolean agentTimeoutTransferMobile;
    @Size(max = 32) private String agentTimeoutTransferNumber;
    @NotNull private Boolean stickyAgentEnabled;
    @NotNull private Boolean queueAnnounceEnabled;
    @NotNull @Min(5) @Max(3600) private Integer queueAnnounceInterval;
    private Long queueAnnounceMediaId;
    @NotNull @Min(10) @Max(86400) private Integer maxWaitSeconds;
    @NotNull @Min(5) @Max(300) private Integer ringTimeoutSeconds;
    @NotNull @Min(0) @Max(100) private Integer maxNoAnswer;
    @NotNull @Min(0) @Max(3600) private Integer wrapUpSeconds;
    @NotNull private Boolean enabled;
    @Size(max = 500) private String remark;
    private Integer version;
}
