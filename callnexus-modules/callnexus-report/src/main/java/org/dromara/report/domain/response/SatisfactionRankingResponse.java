package org.dromara.report.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SatisfactionRankingResponse {
    private Long dimensionId;
    private String dimensionName;
    private Long invitationCount;
    private Long submittedCount;
    private BigDecimal averageScore;
    private BigDecimal participationRate;
    private BigDecimal satisfactionRate;
}
