package org.dromara.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.*;

@Component
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    @Override public String providerType() { return "OPENAI_COMPATIBLE"; }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        try {
            Map<String, Object> body = OpenAiCompatibleSupport.options(request.model());
            body.put("model", request.model().getModelName());
            body.put("input", request.inputs());
            HttpResponse<String> response = OpenAiCompatibleSupport.client(request.provider()).send(
                OpenAiCompatibleSupport.request(request.provider(), "/embeddings", body),
                HttpResponse.BodyHandlers.ofString());
            OpenAiCompatibleSupport.requireSuccess(response.statusCode(), response.body(), "Embedding");
            JsonNode root = JsonUtils.getObjectMapper().readTree(response.body());
            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                List<Double> vector = new ArrayList<>();
                for (JsonNode number : item.path("embedding")) vector.add(number.asDouble());
                vectors.add(vector);
            }
            if (vectors.size() != request.inputs().size() || vectors.stream().anyMatch(List::isEmpty)) {
                throw new ServiceException("Embedding 服务返回的向量数量或内容不正确");
            }
            int dimension = vectors.get(0).size();
            if (vectors.stream().anyMatch(item -> item.size() != dimension)) {
                throw new ServiceException("Embedding 服务返回了不同维度的向量");
            }
            Integer tokens = root.path("usage").path("prompt_tokens").isNumber()
                ? root.path("usage").path("prompt_tokens").asInt() : null;
            return new EmbeddingResult(vectors, tokens);
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Embedding 模型调用被中断");
        } catch (Exception e) {
            throw new ServiceException("Embedding 模型调用异常：" + e.getMessage());
        }
    }

}
