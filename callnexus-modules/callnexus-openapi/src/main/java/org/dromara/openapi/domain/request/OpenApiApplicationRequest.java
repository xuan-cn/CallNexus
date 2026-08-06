package org.dromara.openapi.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OpenApiApplicationRequest {
    @NotBlank
    @Size(max = 64)
    private String appCode;
    @NotBlank
    @Size(max = 128)
    private String appName;
    private Boolean enabled;
    @Min(300)
    @Max(86400)
    private Integer tokenTtlSeconds;
    @Min(1)
    @Max(100000)
    private Integer requestsPerMinute;
    @Min(1)
    @Max(10000)
    private Integer maxConcurrentCalls;
    private Boolean websocketEnabled;
    private Boolean webhookEnabled;
    @Size(max = 500)
    private String webhookUrl;
    @Size(max = 256)
    private String webhookSecret;
    @NotNull
    private List<@NotBlank String> eventTypes = new ArrayList<>();
    @Size(max = 500)
    private String description;
    private Integer version;
    @Size(min = 1)
    @NotNull
    private List<@NotBlank String> scopes = new ArrayList<>();
    @Valid
    @Size(min = 1)
    @NotNull
    private List<OpenApiIpRuleRequest> ipRules = new ArrayList<>();
    @NotNull
    private List<@NotBlank String> routePolicyCodes = new ArrayList<>();
}
