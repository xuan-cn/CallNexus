package org.dromara.ai.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class AiIntentGroupRequest {
    @NotBlank @Size(max = 64) private String groupCode;
    @NotBlank @Size(max = 128) private String groupName;
    @Size(max = 500) private String description;
    @Min(1) @Max(10000) private Integer sortOrder;
    private Boolean enabled;
}
