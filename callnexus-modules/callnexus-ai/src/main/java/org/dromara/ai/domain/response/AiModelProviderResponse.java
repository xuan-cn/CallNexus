package org.dromara.ai.domain.response;

import lombok.Data;
import java.util.Date;

@Data
public class AiModelProviderResponse {
    private Long id;
    private String providerCode;
    private String providerName;
    private String providerType;
    private String baseUrl;
    private Boolean apiKeyConfigured;
    private String organizationId;
    private Integer connectTimeoutSeconds;
    private Integer readTimeoutSeconds;
    private String extraConfigJson;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
