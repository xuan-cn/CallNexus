package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiFaqLearningRejectRequest {
    @NotBlank @Size(max = 500) private String reason;
}
