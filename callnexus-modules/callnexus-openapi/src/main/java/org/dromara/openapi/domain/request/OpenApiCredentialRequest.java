package org.dromara.openapi.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Date;

@Data
public class OpenApiCredentialRequest {
    @NotBlank
    @Size(max = 128)
    private String credentialName;
    private Date expiresAt;
}
