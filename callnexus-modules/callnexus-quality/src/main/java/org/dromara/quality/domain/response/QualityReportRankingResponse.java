package org.dromara.quality.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityReportRankingResponse {
    private Long dimensionId;
    private String dimensionName;
    private Long resultCount;
    private BigDecimal averageScore;
    private Long qualifiedCount;
    private BigDecimal qualifiedRate;
    private Long fatalCount;
}
