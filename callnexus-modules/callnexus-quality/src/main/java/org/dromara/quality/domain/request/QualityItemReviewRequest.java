package org.dromara.quality.domain.request;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class QualityItemReviewRequest {
    @NotNull private Long templateItemId;
    @NotBlank private String result;
    private Integer scoreChange;
    @Size(max = 1000) private String reason;
    private String evidenceJson;
    @Size(max = 1000) private String modificationReason;
}
