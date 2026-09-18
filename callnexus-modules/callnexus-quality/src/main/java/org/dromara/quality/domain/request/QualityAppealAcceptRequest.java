package org.dromara.quality.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class QualityAppealAcceptRequest {
    @NotBlank @Size(max = 2000) private String reviewConclusion;
    @Size(max = 2000) private String summary;
    @Size(max = 2000) private String improvementSuggestion;
    @NotEmpty private List<@Valid QualityItemReviewRequest> items;
}
