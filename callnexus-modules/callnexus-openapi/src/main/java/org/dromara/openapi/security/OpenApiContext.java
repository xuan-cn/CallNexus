package org.dromara.openapi.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OpenApiContext {
    private static final ThreadLocal<OpenApiPrincipal> CURRENT = new ThreadLocal<>();

    public static void set(OpenApiPrincipal principal) {
        CURRENT.set(principal);
    }

    public static OpenApiPrincipal require() {
        OpenApiPrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new OpenApiAuthenticationException(401, "invalid_token", "OpenAPI authentication context is missing.");
        }
        return principal;
    }

    public static void requireScope(String scope) {
        if (!require().hasScope(scope)) {
            throw new OpenApiAuthenticationException(403, "insufficient_scope", "Required scope: " + scope);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
