package org.dromara.quality.domain.response;

import lombok.Data;

@Data
public class QualityAiModificationResponse {
    private String itemCode;
    private String itemName;
    private Long evaluatedCount;
    private Long modifiedCount;
}
