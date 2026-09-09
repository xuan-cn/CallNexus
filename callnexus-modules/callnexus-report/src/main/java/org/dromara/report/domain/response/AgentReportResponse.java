package org.dromara.report.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AgentReportResponse {
    private Long agentId;
    private String agentCode;
    private String agentName;
    private String extension;
    private String skillGroupNames;
    private Long handledCount;
    private Long inboundAnsweredCount;
    private Long outboundAnsweredCount;
    private Long missedCount;
    private Long totalTalkSeconds;
    private Long averageTalkSeconds;
    private Long averageResponseSeconds;
    private Long onlineSeconds;
    private Long notReadySeconds;
    private Long afterCallSeconds;
    private BigDecimal utilizationRate;
}
