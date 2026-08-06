package org.dromara.openapi.service;

import org.dromara.openapi.domain.response.OpenApiTokenResponse;
import org.dromara.openapi.security.OpenApiPrincipal;

public interface OpenApiTokenService {
    OpenApiTokenResponse issue(String grantType, String clientId, String clientSecret, String requestedScope, String remoteAddress);
    OpenApiPrincipal authenticate(String accessToken, String remoteAddress);
}
