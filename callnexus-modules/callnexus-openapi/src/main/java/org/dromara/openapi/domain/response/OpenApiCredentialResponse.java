package org.dromara.openapi.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class OpenApiCredentialResponse {
    private Long id;
    private Long applicationId;
    private String credentialName;
    private String clientId;
    private String secretHint;
    private String status;
    private Date expiresAt;
    private Date lastUsedAt;
    private Date createTime;
}
