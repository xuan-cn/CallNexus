package org.dromara.openapi.websocket;

import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.openapi.domain.OpenApiApplication;
import org.dromara.openapi.mapper.OpenApiApplicationMapper;
import org.dromara.openapi.security.OpenApiAuthenticationException;
import org.dromara.openapi.security.OpenApiPrincipal;
import org.dromara.openapi.security.OpenApiRemoteAddressResolver;
import org.dromara.openapi.service.OpenApiTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OpenApiEventHandshakeInterceptor implements HandshakeInterceptor {
    private final OpenApiTokenService tokenService;
    private final OpenApiRemoteAddressResolver remoteAddressResolver;
    private final OpenApiApplicationMapper applicationMapper;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("access_token");
            String remoteAddress = request instanceof ServletServerHttpRequest servlet
                ? remoteAddressResolver.resolve(servlet.getServletRequest())
                : request.getRemoteAddress() == null ? "" : request.getRemoteAddress().getAddress().getHostAddress();
            OpenApiPrincipal principal = tokenService.authenticate(token, remoteAddress);
            if (!principal.hasScope("event.subscribe")) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            OpenApiApplication application = TenantHelper.dynamic(principal.tenantId(),
                () -> applicationMapper.selectById(principal.applicationId()));
            if (application == null || !Boolean.TRUE.equals(application.getWebsocketEnabled())) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            List<String> subscribedEvents = application.getSubscribedEvents() == null
                || application.getSubscribedEvents().isBlank()
                ? List.of()
                : JsonUtils.parseArray(application.getSubscribedEvents(), String.class);
            attributes.put(OpenApiEventWebSocketHandler.PRINCIPAL_ATTRIBUTE, principal);
            attributes.put(OpenApiEventWebSocketHandler.SUBSCRIBED_EVENTS_ATTRIBUTE, subscribedEvents);
            return true;
        } catch (OpenApiAuthenticationException exception) {
            response.setStatusCode(HttpStatus.valueOf(exception.getStatus()));
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
