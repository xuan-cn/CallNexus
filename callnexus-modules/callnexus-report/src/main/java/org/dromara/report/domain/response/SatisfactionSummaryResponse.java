package org.dromara.report.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SatisfactionSummaryResponse {
    private Long invitationCount;
    private Long submittedCount;
    private Long noInputCount;
    private BigDecimal averageScore;
    private BigDecimal participationRate;
    private BigDecimal satisfactionRate;
    private BigDecimal dissatisfactionRate;
    private BigDecimal noInputRate;
}
