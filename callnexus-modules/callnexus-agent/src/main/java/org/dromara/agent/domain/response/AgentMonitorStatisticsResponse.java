package org.dromara.agent.domain.response;

import lombok.Data;

@Data
public class AgentMonitorStatisticsResponse {
    private Long agentId;
    private Long todayHandledCount;
    private Long inboundAnsweredCount;
    private Long outboundAnsweredCount;
    private Long missedOfferCount;
    private Long totalTalkSeconds;
    private Long averageTalkSeconds;
}
