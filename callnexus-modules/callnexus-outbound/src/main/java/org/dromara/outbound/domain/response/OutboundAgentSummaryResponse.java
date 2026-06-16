package org.dromara.outbound.domain.response;

import lombok.Data;

@Data
public class OutboundAgentSummaryResponse {
    private Long agentId;
    private String agentCode;
    private String agentName;
    private long attemptCount;
    private long answeredCount;
    private long connectedCount;
    private long customerCount;
    private long totalDurationSeconds;
    private long billableSeconds;
    private double answerRate;
    private double connectionRate;
}
