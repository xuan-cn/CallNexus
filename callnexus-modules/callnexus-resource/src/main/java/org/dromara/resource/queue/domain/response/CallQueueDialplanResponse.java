package org.dromara.resource.queue.domain.response;

import lombok.Data;

@Data
public class CallQueueDialplanResponse {
    private Long id;
    private String queueCode;
    private String queueName;
    private Boolean maskCallerNumber;
    private Integer forceWaitSeconds;
    private String forceWaitMediaPath;
    private String timeoutAction;
    private String timeoutTarget;
    private String timeoutTargetQueueCode;
    private String noAgentAction;
    private String noAgentTarget;
    private String noAgentTargetQueueCode;
    private Integer noAgentWaitSeconds;
}
