package org.dromara.openapi.controller;

import org.dromara.openapi.security.OpenApiContext;
import org.dromara.openapi.security.OpenApiPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class OpenApiPingController {
    @GetMapping("/openapi/v1/ping")
    public Map<String, Object> ping() {
        OpenApiPrincipal principal = OpenApiContext.require();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("application_id", principal.applicationId());
        result.put("app_code", principal.appCode());
        result.put("tenant_id", principal.tenantId());
        result.put("scopes", principal.scopes());
        return result;
    }
}
