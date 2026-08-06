package org.dromara.openapi.config;

import lombok.RequiredArgsConstructor;
import org.dromara.openapi.websocket.OpenApiEventHandshakeInterceptor;
import org.dromara.openapi.websocket.OpenApiEventWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class OpenApiEventWebSocketConfiguration implements WebSocketConfigurer {
    private final OpenApiEventWebSocketHandler handler;
    private final OpenApiEventHandshakeInterceptor interceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/openapi/ws/events").addInterceptors(interceptor).setAllowedOrigins("*");
    }
}
