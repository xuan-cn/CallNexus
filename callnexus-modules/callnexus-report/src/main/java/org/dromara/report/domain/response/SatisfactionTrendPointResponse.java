package org.dromara.report.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SatisfactionTrendPointResponse {
    private String bucket;
    private Long invitationCount;
    private Long submittedCount;
    private Long satisfiedCount;
    private BigDecimal averageScore;
    private BigDecimal participationRate;
    private BigDecimal satisfactionRate;
}
