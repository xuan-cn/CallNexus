package org.dromara.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private static final Pattern BATCH_LIMIT_PATTERN = Pattern.compile(
        "(?i)batch size.*?(?:not be larger than|maximum|max(?:imum)?(?: is| of)?)\\s*(\\d+)");
    private final ConcurrentMap<String, Integer> learnedBatchLimits = new ConcurrentHashMap<>();

    @Override public String providerType() { return "OPENAI_COMPATIBLE"; }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        if (request.inputs() == null || request.inputs().isEmpty()) {
            throw new ServiceException("Embedding 输入不能为空");
        }
        int batchSize = configuredBatchSize(request);
        try {
            return embedInBatches(request, batchSize);
        } catch (ServiceException exception) {
            Integer upstreamLimit = extractBatchLimit(exception);
            if (upstreamLimit == null || upstreamLimit <= 0 || batchSize <= upstreamLimit) {
                throw exception;
            }
            learnedBatchLimits.put(batchLimitKey(request), upstreamLimit);
            log.warn("Embedding 上游限制单次批量，已自动调整并重试，providerCode={}，model={}，configuredBatchSize={}，upstreamBatchSize={}",
                request.provider().getProviderCode(), request.model().getModelName(), batchSize, upstreamLimit);
            return embedInBatches(request, upstreamLimit);
        }
    }

    private EmbeddingResult embedInBatches(EmbeddingRequest request, int batchSize) {
        List<List<Double>> vectors = new ArrayList<>(request.inputs().size());
        Integer totalTokens = null;
        for (int offset = 0; offset < request.inputs().size(); offset += batchSize) {
            List<String> inputs = List.copyOf(request.inputs().subList(offset,
                Math.min(request.inputs().size(), offset + batchSize)));
            EmbeddingResult result = embedSingleBatch(new EmbeddingRequest(request.provider(), request.model(), inputs));
            vectors.addAll(result.vectors());
            if (result.inputTokens() != null) {
                totalTokens = (totalTokens == null ? 0 : totalTokens) + result.inputTokens();
            }
        }
        return new EmbeddingResult(vectors, totalTokens);
    }

    private EmbeddingResult embedSingleBatch(EmbeddingRequest request) {
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

    private int configuredBatchSize(EmbeddingRequest request) {
        int configured = request.model().getMaxBatchSize() == null
            ? request.inputs().size() : Math.max(1, request.model().getMaxBatchSize());
        Integer learned = learnedBatchLimits.get(batchLimitKey(request));
        return Math.min(request.inputs().size(), learned == null ? configured : Math.min(configured, learned));
    }

    private String batchLimitKey(EmbeddingRequest request) {
        return request.provider().getId() + ":" + request.model().getId();
    }

    static Integer extractBatchLimit(ServiceException exception) {
        String message = exception.getMessage();
        if (message == null) return null;
        Matcher matcher = BATCH_LIMIT_PATTERN.matcher(message);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
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
