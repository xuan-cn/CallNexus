package org.dromara.resource.freeswitch.xmlcurl.route;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class DialplanRouteHandlerRegistry {

    private final Map<String, DialplanRouteHandler> handlers;

    public DialplanRouteHandlerRegistry(List<DialplanRouteHandler> handlers) {
        Map<String, DialplanRouteHandler> registered = new LinkedHashMap<>();
        for (DialplanRouteHandler handler : handlers) {
            String routeType = normalize(handler.routeType());
            DialplanRouteHandler duplicated = registered.putIfAbsent(routeType, handler);
            if (duplicated != null) {
                throw new IllegalStateException("重复的 Dialplan 路由处理器类型: " + routeType);
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public Optional<DialplanRouteHandler> find(String routeType) {
        if (routeType == null || routeType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(normalize(routeType)));
    }

    private String normalize(String routeType) {
        if (routeType == null || routeType.isBlank()) {
            throw new IllegalStateException("Dialplan 路由处理器类型不能为空");
        }
        return routeType.trim().toUpperCase(Locale.ROOT);
    }
}
