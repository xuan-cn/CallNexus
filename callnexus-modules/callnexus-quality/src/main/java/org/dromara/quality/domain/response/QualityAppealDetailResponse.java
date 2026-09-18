package org.dromara.quality.domain.response;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityAppealDetailResponse extends QualityAppealResponse {
    private QualityTaskDetailResponse task;
    private QualityResultResponse originalResult;
    private QualityResultResponse reviewResult;
}
