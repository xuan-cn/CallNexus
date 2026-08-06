package org.dromara.openapi.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

public record OpenApiPrincipal(
    Long applicationId,
    Long credentialId,
    Integer credentialVersion,
    String tenantId,
    String appCode,
    Set<String> scopes
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }
}
