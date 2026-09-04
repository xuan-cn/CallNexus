package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class AiIntentBatchUpdateRequest {
    @NotEmpty private List<Long> intentIds;
    private Long groupId;
    private Boolean clearGroup;
    private Boolean enabled;
}
