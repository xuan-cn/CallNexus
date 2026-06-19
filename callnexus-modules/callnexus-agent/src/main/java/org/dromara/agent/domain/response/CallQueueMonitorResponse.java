package org.dromara.agent.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallQueueMonitorResponse {
    private Long queueId;
    private String queueCode;
    private String queueName;
    private String nodeGroupName;
    private String skillGroupName;
    private String syncStatus;
    private String syncError;
    private LocalDateTime lastSyncedAt;
    private Boolean enabled;
    private Integer maxWaitSeconds;

    private Long enteredCount;
    private Long answeredCount;
    private Long abandonedCount;
    private Long timeoutCount;
    private Long waitingCount;
    private Long ringingCount;
    private Long totalAgentCount;
    private Long onlineAgentCount;
    private Long idleAgentCount;
    private Long busyAgentCount;
    private Long offlineAgentCount;
    private Long averageWaitSeconds;
    private Long longestWaitSeconds;
    private Long answerRate;
    private Long abandonRate;

    private String healthStatus;
    private String healthText;
}
