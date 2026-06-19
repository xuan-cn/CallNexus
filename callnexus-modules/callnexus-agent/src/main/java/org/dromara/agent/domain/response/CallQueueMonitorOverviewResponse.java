package org.dromara.agent.domain.response;

import lombok.Data;

@Data
public class CallQueueMonitorOverviewResponse {
    private Long queueCount;
    private Long healthyQueueCount;
    private Long warningQueueCount;
    private Long abnormalQueueCount;
    private Long currentWaitingCount;
    private Long currentRingingCount;
    private Long totalAgentCount;
    private Long onlineAgentCount;
    private Long idleAgentCount;
    private Long busyAgentCount;
    private Long todayEnteredCount;
    private Long todayAnsweredCount;
    private Long todayAbandonedCount;
    private Long todayTimeoutCount;
    private Long averageWaitSeconds;
    private Long longestWaitSeconds;
    private Long answerRate;
    private Long abandonRate;
}
