package org.dromara.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.ai.domain.AiModel;
import org.dromara.ai.domain.AiModelProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class OpenAiCompatibleSupport {
    private static final Map<Integer, HttpClient> CLIENTS = new ConcurrentHashMap<>();

    private OpenAiCompatibleSupport() {}

    static HttpClient client(AiModelProvider provider) {
        int connectTimeoutSeconds = defaultValue(provider.getConnectTimeoutSeconds(), 10);
        return CLIENTS.computeIfAbsent(connectTimeoutSeconds, timeout -> HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout))
            // OpenAI 兼容服务、反向代理和 SSH 隧道对 HTTP/2 长连接的支持并不一致。
            // HTTP/1.1 可避免空闲连接被网关关闭后首次请求频繁出现 Connection reset。
            .version(HttpClient.Version.HTTP_1_1)
            .build());
    }

    static HttpRequest request(AiModelProvider provider, String path, Object body) {
        String base = provider.getBaseUrl().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(defaultValue(provider.getReadTimeoutSeconds(), 120)))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJsonString(body)));
        if (StringUtils.isNotBlank(provider.getApiKey())) {
            builder.header("Authorization", "Bearer " + provider.getApiKey());
        }
        if (StringUtils.isNotBlank(provider.getOrganizationId())) {
            builder.header("OpenAI-Organization", provider.getOrganizationId());
        }
        return builder.build();
    }

    static Map<String, Object> options(AiModel model) {
        if (StringUtils.isBlank(model.getRequestOptionsJson())) return new LinkedHashMap<>();
        JsonNode node;
        try {
            node = JsonUtils.getObjectMapper().readTree(model.getRequestOptionsJson());
            if (!node.isObject()) throw new IllegalArgumentException();
            return JsonUtils.getObjectMapper().convertValue(node, Map.class);
        } catch (Exception e) {
            throw new ServiceException("模型扩展参数不是合法 JSON 对象");
        }
    }

    static void requireSuccess(int status, String body, String capability) {
        if (status >= 200 && status < 300) return;
        String safe = body == null ? "" : body.replaceAll("(?i)(api[_-]?key|token)[^,}]*", "$1=***");
        if (safe.length() > 1000) safe = safe.substring(0, 1000);
        throw new ServiceException(capability + " 模型调用失败，HTTP状态码=" + status + "，响应=" + safe);
    }

    static int defaultValue(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
