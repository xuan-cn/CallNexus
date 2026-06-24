package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiTtsProviderRequest {
    private Long id;
    @NotBlank private String providerCode;
    @NotBlank private String providerName;
    @NotBlank private String providerType;
    @NotBlank private String endpointUrl;
    private String httpMethod;
    private String authType;
    private String authHeaderName;
    private String authToken;
    private String defaultVoice;
    private String defaultFormat;
    private Integer defaultSampleRate;
    private Integer timeoutSeconds;
    private Boolean enabled;
    private String remark;
    private Integer version;
}
