package org.dromara.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
public class OpenAiCompatibleChatProvider implements ChatProvider {
    @Override public String providerType() { return "OPENAI_COMPATIBLE"; }

    @Override
    public ChatResult chat(ChatRequest request) {
        try {
            Map<String, Object> body = body(request, false);
            HttpResponse<String> response = OpenAiCompatibleSupport.client(request.provider()).send(
                OpenAiCompatibleSupport.request(request.provider(), "/chat/completions", body),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            OpenAiCompatibleSupport.requireSuccess(response.statusCode(), response.body(), "Chat");
            JsonNode root = JsonUtils.getObjectMapper().readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                String reasoning = message.path("reasoning").asText("");
                if (reasoning.isBlank()) reasoning = message.path("reasoning_content").asText("");
                if (!reasoning.isBlank()) {
                    throw new ServiceException("Chat 模型只返回了推理内容，尚未生成最终回答；请关闭思考模式或提高最大输出 Token");
                }
                throw new ServiceException("Chat 模型未返回回答内容");
            }
            return new ChatResult(content,
                number(root, "prompt_tokens"), number(root, "completion_tokens"));
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Chat 模型调用被中断");
        } catch (Exception e) {
            throw new ServiceException("Chat 模型调用异常：" + e.getMessage());
        }
    }

    @Override
    public ChatResult stream(ChatRequest request, Consumer<String> deltaConsumer) {
        StringBuilder answer = new StringBuilder();
        try {
            HttpResponse<Stream<String>> response = OpenAiCompatibleSupport.client(request.provider()).send(
                OpenAiCompatibleSupport.request(request.provider(), "/chat/completions", body(request, true)),
                java.net.http.HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error;
                try (Stream<String> lines = response.body()) { error = String.join("\n", lines.toList()); }
                OpenAiCompatibleSupport.requireSuccess(response.statusCode(), error, "Chat");
            }
            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    JsonNode root = JsonUtils.getObjectMapper().readTree(data);
                    String delta = root.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        answer.append(delta);
                        deltaConsumer.accept(delta);
                    }
                }
            }
            if (answer.isEmpty()) throw new ServiceException("Chat 模型未返回回答内容");
            return new ChatResult(answer.toString(), null, null);
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Chat 模型调用被中断");
        } catch (Exception e) {
            throw new ServiceException("Chat 流式调用异常：" + e.getMessage());
        }
    }

    private Map<String, Object> body(ChatRequest request, boolean stream) {
        Map<String, Object> body = OpenAiCompatibleSupport.options(request.model());
        body.put("model", request.model().getModelName());
        body.put("messages", request.messages());
        body.put("stream", stream);
        // CallNexus 全局会把 BigDecimal 序列化为字符串，而 OpenAI 协议要求 temperature 是 JSON 数字。
        if (request.temperature() != null) body.put("temperature", request.temperature().doubleValue());
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
        return body;
    }

    private Integer number(JsonNode root, String field) {
        JsonNode value = root.path("usage").path(field);
        return value.isNumber() ? value.asInt() : null;
    }
}
