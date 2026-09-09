package org.dromara.agent.domain.response;

import lombok.Data;

@Data
public class AgentMonitorOverviewResponse {
    private Long totalAgentCount;
    private Long onlineAgentCount;
    private Long idleAgentCount;
    private Long notReadyAgentCount;
    private Long busyAgentCount;
    private Long afterCallAgentCount;
    private Long offlineAgentCount;
    private Long todayHandledCount;
    private Long inboundAnsweredCount;
    private Long outboundAnsweredCount;
    private Long missedOfferCount;
    private Long totalTalkSeconds;
    private Long averageTalkSeconds;
    private Long onlineSeconds;
    private Long idleSeconds;
    private Long notReadySeconds;
    private Long busySeconds;
    private Long afterCallSeconds;
    private Double utilizationRate;
}
