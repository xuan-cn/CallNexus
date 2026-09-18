package org.dromara.quality.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityReportTrendResponse {
    private String bucket;
    private Long eligibleCallCount;
    private Long reviewedCallCount;
    private BigDecimal coverageRate;
    private Long resultCount;
    private BigDecimal averageScore;
    private Long qualifiedCount;
    private BigDecimal qualifiedRate;
}
