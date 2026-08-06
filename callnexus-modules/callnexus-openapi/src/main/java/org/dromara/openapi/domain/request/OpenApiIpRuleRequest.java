package org.dromara.openapi.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OpenApiIpRuleRequest {
    @NotBlank
    @Size(max = 64)
    private String cidr;
    @Size(max = 255)
    private String description;
    private Boolean enabled;
}
