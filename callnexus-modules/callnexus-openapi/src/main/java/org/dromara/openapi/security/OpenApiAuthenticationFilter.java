package org.dromara.openapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.openapi.service.OpenApiTokenService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class OpenApiAuthenticationFilter extends OncePerRequestFilter {
    private static final String API_PREFIX = "/openapi/v1/";
    private static final String REQUEST_ID_HEADER = "X-CallNexus-Request-Id";

    private final OpenApiTokenService tokenService;
    private final OpenApiRemoteAddressResolver remoteAddressResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            OpenApiPrincipal principal = tokenService.authenticate(bearerToken(request), remoteAddressResolver.resolve(request));
            OpenApiContext.set(principal);
            TenantHelper.setDynamic(principal.tenantId());
            filterChain.doFilter(request, response);
        } catch (OpenApiAuthenticationException exception) {
            writeError(response, exception, requestId);
        } finally {
            OpenApiContext.clear();
            TenantHelper.clearDynamic();
        }
    }

    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new OpenApiAuthenticationException(401, "invalid_token", "Authorization: Bearer token is required.");
        }
        return authorization.substring(7).trim();
    }

    private void writeError(HttpServletResponse response, OpenApiAuthenticationException exception, String requestId)
        throws IOException {
        response.setStatus(exception.getStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", exception.getError());
        body.put("error_description", exception.getMessage());
        body.put("request_id", requestId);
        response.getWriter().write(JsonUtils.toJsonString(body));
    }
}
