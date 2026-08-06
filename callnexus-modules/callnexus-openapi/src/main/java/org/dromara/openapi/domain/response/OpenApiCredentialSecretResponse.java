package org.dromara.openapi.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OpenApiCredentialSecretResponse {
    private Long credentialId;
    private String clientId;
    private String clientSecret;
    private String warning;
}
