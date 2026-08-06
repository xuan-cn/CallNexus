package org.dromara.openapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dromara.openapi.domain.response.OpenApiTokenResponse;
import org.dromara.openapi.security.OpenApiAuthenticationException;
import org.dromara.openapi.security.OpenApiRemoteAddressResolver;
import org.dromara.openapi.service.OpenApiTokenService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OpenApiTokenController {
    private final OpenApiTokenService tokenService;
    private final OpenApiRemoteAddressResolver remoteAddressResolver;

    @PostMapping(value = "/openapi/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> token(@RequestParam(name = "grant_type") String grantType,
                                   @RequestParam(name = "client_id", required = false) String clientId,
                                   @RequestParam(name = "client_secret", required = false) String clientSecret,
                                   @RequestParam(name = "scope", required = false) String scope,
                                   HttpServletRequest request) {
        try {
            String[] credentials = basicCredentials(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (credentials != null) {
                clientId = credentials[0];
                clientSecret = credentials[1];
            }
            OpenApiTokenResponse body = tokenService.issue(grantType, clientId, clientSecret, scope,
                remoteAddressResolver.resolve(request));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(body);
        } catch (OpenApiAuthenticationException exception) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", exception.getError());
            body.put("error_description", exception.getMessage());
            return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
        }
    }

    private String[] basicCredentials(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException();
            }
            return new String[]{decoded.substring(0, separator), decoded.substring(separator + 1)};
        } catch (Exception exception) {
            throw new OpenApiAuthenticationException(401, "invalid_client", "Invalid HTTP Basic credentials.");
        }
    }
}
