package org.dromara.openapi.domain.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class OpenApiApplicationResponse {
    private Long id;
    private String appCode;
    private String appName;
    private Boolean enabled;
    private Integer tokenTtlSeconds;
    private Integer requestsPerMinute;
    private Integer maxConcurrentCalls;
    private Boolean websocketEnabled;
    private Boolean webhookEnabled;
    private String webhookUrl;
    private Boolean webhookSecretConfigured;
    private List<String> eventTypes = new ArrayList<>();
    private String description;
    private Integer version;
    private Date createTime;
    private List<String> scopes = new ArrayList<>();
    private List<OpenApiIpRuleResponse> ipRules = new ArrayList<>();
    private List<String> routePolicyCodes = new ArrayList<>();
}
