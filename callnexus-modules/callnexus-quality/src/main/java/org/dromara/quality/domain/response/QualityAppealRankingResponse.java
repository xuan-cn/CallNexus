package org.dromara.quality.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityAppealRankingResponse {
    private Long dimensionId;
    private String dimensionName;
    private Long publishedCount;
    private Long appealedCount;
    private Long acceptedCount;
    private Long completedCount;
    private BigDecimal appealRate;
    private BigDecimal successRate;
}
