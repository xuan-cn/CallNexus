package org.dromara.ai.vector;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
public class QdrantVectorStore implements VectorStore {
    private final AiKnowledgeProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_2)
        .build();

    @Override
    public void ensureCollection(String collection, int dimension) {
        HttpResponse<String> current = send("GET", "/collections/" + collection, null);
        if (current.statusCode() == 200) return;
        if (current.statusCode() != 404) requireSuccess(current, "查询 Qdrant Collection");
        Map<String, Object> body = Map.of("vectors", Map.of("size", dimension, "distance", "Cosine"));
        requireSuccess(send("PUT", "/collections/" + collection, body), "创建 Qdrant Collection");
    }

    @Override
    public void upsert(String collection, List<VectorPoint> points) {
        if (points.isEmpty()) return;
        List<Map<String, Object>> values = points.stream().map(point -> Map.<String, Object>of(
            "id", point.id(), "vector", point.vector(), "payload", point.payload())).toList();
        requireSuccess(send("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", values)),
            "写入 Qdrant 向量");
    }

    @Override
    public List<VectorSearchHit> search(String collection, List<Double> vector, Map<String, Object> filters, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", Math.max(1, limit));
        body.put("with_payload", true);
        body.put("filter", qdrantFilter(filters));
        HttpResponse<String> response = send("POST", "/collections/" + collection + "/points/search", body);
        requireSuccess(response, "检索 Qdrant 向量");
        try {
            JsonNode root = JsonUtils.getObjectMapper().readTree(response.body());
            List<VectorSearchHit> hits = new ArrayList<>();
            for (JsonNode item : root.path("result")) {
                Map<String, Object> payload = JsonUtils.getObjectMapper().convertValue(item.path("payload"), Map.class);
                hits.add(new VectorSearchHit(item.path("id").asText(), item.path("score").asDouble(), payload));
            }
            return hits;
        } catch (Exception e) {
            throw new ServiceException("解析 Qdrant 检索结果失败：" + e.getMessage());
        }
    }

    @Override
    public void setPayload(String collection, List<String> pointIds, Map<String, Object> payload) {
        if (pointIds.isEmpty()) return;
        requireSuccess(send("POST", "/collections/" + collection + "/points/payload?wait=true",
            Map.of("payload", payload, "points", pointIds)), "更新 Qdrant Payload");
    }

    @Override
    public void deleteByFilter(String collection, Map<String, Object> filters) {
        requireSuccess(send("POST", "/collections/" + collection + "/points/delete?wait=true",
            Map.of("filter", qdrantFilter(filters))), "删除 Qdrant 向量");
    }

    private Map<String, Object> qdrantFilter(Map<String, Object> filters) {
        List<Map<String, Object>> must = new ArrayList<>();
        filters.forEach((key, value) -> {
            Map<String, Object> match = value instanceof Collection<?> values
                ? Map.of("any", values) : Map.of("value", value);
            must.add(Map.of("key", key, "match", match));
        });
        return Map.of("must", must);
    }

    private HttpResponse<String> send(String method, String path, Object body) {
        try {
            String base = properties.getQdrantUrl().replaceAll("/+$", "");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (StringUtils.isNotBlank(properties.getQdrantApiKey())) {
                builder.header("api-key", properties.getQdrantApiKey());
            }
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.method(method, HttpRequest.BodyPublishers.ofString(JsonUtils.toJsonString(body)));
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Qdrant 请求被中断");
        } catch (Exception e) {
            throw new ServiceException("Qdrant 请求失败：" + e.getMessage());
        }
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) return;
        String message = response.body() == null ? "" : response.body();
        if (message.length() > 1000) message = message.substring(0, 1000);
        throw new ServiceException(operation + "失败，HTTP状态码=" + response.statusCode() + "，响应=" + message);
    }
}
