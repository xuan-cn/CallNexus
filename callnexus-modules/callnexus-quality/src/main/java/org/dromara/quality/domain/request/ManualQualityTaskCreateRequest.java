package org.dromara.quality.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class ManualQualityTaskCreateRequest {
    @NotNull private Long templateId;
    @NotEmpty private List<Long> callSessionIds;
    @Min(1) @Max(9) private Integer priority = 5;
    private Long reviewerId;
}
