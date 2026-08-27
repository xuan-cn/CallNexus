package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AiFaqLearningMergeRequest {
    @NotNull private Long targetFaqId;
}
