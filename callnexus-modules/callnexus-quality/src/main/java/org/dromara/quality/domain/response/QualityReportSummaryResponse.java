package org.dromara.quality.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityReportSummaryResponse {
    private Long eligibleCallCount;
    private Long reviewedCallCount;
    private BigDecimal coverageRate;
    private Long resultCount;
    private BigDecimal averageScore;
    private Long qualifiedCount;
    private BigDecimal qualifiedRate;
    private Long fatalCount;
    private BigDecimal fatalRate;
}
