package org.dromara.quality.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityAppealSummaryResponse {
    private Long publishedTaskCount;
    private Long appealCount;
    private Long appealedTaskCount;
    private Long pendingCount;
    private Long maintainedCount;
    private Long adjustedCount;
    private Long recheckedCount;
    private BigDecimal appealRate;
    private BigDecimal successRate;
}
