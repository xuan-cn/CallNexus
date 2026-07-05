package org.dromara.ai.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class AiModelProviderRequest {
    @NotBlank private String providerCode;
    @NotBlank private String providerName;
    @NotBlank private String providerType;
    @NotBlank private String baseUrl;
    private String apiKey;
    private String organizationId;
    @Min(1) @Max(120) private Integer connectTimeoutSeconds;
    @Min(1) @Max(600) private Integer readTimeoutSeconds;
    private String extraConfigJson;
    private Boolean enabled;
    private Integer version;
}
