package org.dromara.quality.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QualityAppealDecisionRequest {
    @NotBlank
    @Size(max = 2000)
    private String reviewConclusion;
}
