package org.dromara.quality.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityCalibrationResponse {
    private Long reviewerId;
    private String reviewerName;
    private Long reviewedCount;
    private BigDecimal averageScore;
    private Long appealedCount;
    private Long adjustedCount;
    private BigDecimal adjustmentRate;
}
