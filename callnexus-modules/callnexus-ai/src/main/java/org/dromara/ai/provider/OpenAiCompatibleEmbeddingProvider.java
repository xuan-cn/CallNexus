package org.dromara.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.*;

@Component
@Slf4j
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    @Override public String providerType() { return "OPENAI_COMPATIBLE"; }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        IOException transportFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return execute(request);
            } catch (IOException exception) {
                transportFailure = exception;
                if (attempt == 1) {
                    OpenAiCompatibleSupport.invalidateClient(request.provider());
                    log.warn("Embedding 连接在响应前被关闭，已淘汰失效连接并重试一次，providerCode={}，model={}，error={}",
                        request.provider().getProviderCode(), request.model().getModelName(), exception.getMessage());
                    continue;
                }
                break;
            } catch (ServiceException exception) {
                if (attempt == 1 && isRetryableGatewayFailure(exception)) {
                    OpenAiCompatibleSupport.invalidateClient(request.provider());
                    log.warn("Embedding 上游网关暂时不可用，已淘汰连接并准备重试一次，providerCode={}，model={}，error={}",
                        request.provider().getProviderCode(), request.model().getModelName(), exception.getMessage());
                    pauseBeforeRetry();
                    continue;
                }
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ServiceException("Embedding 模型调用被中断");
            } catch (Exception exception) {
                throw new ServiceException("Embedding 模型调用异常：" + exception.getMessage());
            }
        }
        throw new ServiceException("Embedding 模型调用异常：" + transportFailure.getMessage());
    }

    static boolean isRetryableGatewayFailure(ServiceException exception) {
        String message = exception.getMessage();
        return message != null && message.matches("(?s).*HTTP状态码=(502|503|504).*?");
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Embedding 模型调用被中断");
        }
    }

    private EmbeddingResult execute(EmbeddingRequest request) throws Exception {
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
            throw e;
        } catch (IOException e) {
            throw e;
        }
    }

}
