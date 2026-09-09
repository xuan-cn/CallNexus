package org.dromara.report.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OverviewKpiResponse {
    private Long totalCalls;
    private Long inboundCalls;
    private Long outboundCalls;
    private Long answeredCalls;
    private Long unansweredCalls;
    private BigDecimal answerRate;
    private Long totalTalkSeconds;
    private Long averageTalkSeconds;
    private Long onlineAgents;
    private Long waitingCalls;
}
