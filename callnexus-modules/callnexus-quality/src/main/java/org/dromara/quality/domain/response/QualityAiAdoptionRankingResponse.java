package org.dromara.quality.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QualityAiAdoptionRankingResponse {
    private Long dimensionId;
    private String dimensionName;
    private Long aiTaskCount;
    private Long evaluatedItemCount;
    private Long adoptedItemCount;
    private Long modifiedItemCount;
    private BigDecimal adoptionRate;
}
