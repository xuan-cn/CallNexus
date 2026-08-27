package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AiFaqLearningBatchRequest {
    @NotEmpty @Size(max = 100) private List<Long> candidateIds;
    private Long targetFaqId;
    @Size(max = 500) private String reason;
}
