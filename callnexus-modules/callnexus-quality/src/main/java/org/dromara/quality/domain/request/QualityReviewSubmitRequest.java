package org.dromara.quality.domain.request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
@Data
public class QualityReviewSubmitRequest {
    @Size(max = 2000) private String summary;
    @Size(max = 2000) private String improvementSuggestion;
    @NotEmpty private List<@Valid QualityItemReviewRequest> items;
}
