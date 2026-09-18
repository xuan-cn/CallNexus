package org.dromara.quality.domain.response;

import lombok.Data;

@Data
public class QualityDeductionItemResponse {
    private String itemCode;
    private String itemName;
    private Long occurrenceCount;
    private Long affectedCallCount;
    private Long totalDeduction;
}
