package org.dromara.ai.provider;

import cn.hutool.core.lang.Dict;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼 DashScope TTS 适配器。
 * <p>
 * 默认使用百炼 Workspace generation 接口，业务层仍然只依赖统一的 TTS Provider 抽象。
 */
@Component
@Slf4j
public class AliyunDashScopeTtsProvider implements TtsProvider {

    private static final String TYPE = "ALIYUN_DASHSCOPE";
    private static final String DEFAULT_ENDPOINT_TEMPLATE = "https://{workspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DEFAULT_OPENAI_MODEL = "qwen-tts";
    private static final String DEFAULT_OPENAI_VOICE = "Cherry";
    private static final String DEFAULT_GENERATION_MODEL = "cosyvoice-v1";
    private static final String DEFAULT_GENERATION_VOICE = "longxiaochun";

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public TtsGenerateResult generate(AiSpeechProvider provider, TtsGenerateRequest request) {
        if (StringUtils.isBlank(provider.getAuthToken())) {
            throw new ServiceException("阿里云百炼 TTS API Key 不能为空");
        }
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout(provider)))
            .build();
        HttpResponse<byte[]> response = send(client, buildRequest(provider, request));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("阿里云百炼 TTS 调用失败，HTTP状态码=" + response.statusCode()
                + "，响应=" + safeBody(response.body()));
        }
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("application/octet-stream");
        if (!contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return new TtsGenerateResult(response.body(), contentType, suffix(contentType, request.format()), null);
        }
        return parseJsonResult(client, provider, response.body(), request.format());
    }

    private HttpRequest buildRequest(AiSpeechProvider provider, TtsGenerateRequest request) {
        String endpoint = endpoint(provider);
        if (endpoint.contains("/audio/tts/SpeechSynthesizer")) {
            return buildMaasSpeechSynthesizerRequest(provider, request, endpoint);
        }
        if (isOpenAiCompatibleEndpoint(endpoint)) {
            return buildOpenAiCompatibleRequest(provider, request, speechEndpoint(endpoint));
        }
        return buildGenerationRequest(provider, request, endpoint);
    }

    private HttpRequest buildOpenAiCompatibleRequest(AiSpeechProvider provider, TtsGenerateRequest request, String endpoint) {
        Dict config = remarkConfig(provider);
        String format = format(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model(provider, true, config));
        payload.put("input", request.text());
        payload.put("voice", chooseVoice(provider, request, true, config));
        payload.put("response_format", format);
        payload.putAll(mapAt(config, "parameters"));

        String payloadJson = JsonUtils.toJsonString(payload);
        log.info("调用阿里云百炼 OpenAI兼容 TTS，providerCode={}，model={}，voice={}，format={}，endpoint={}",
            provider.getProviderCode(), payload.get("model"), payload.get("voice"), payload.get("response_format"), endpoint);
        log.debug("阿里云百炼 OpenAI兼容 TTS 请求体={}", payloadJson);

        return HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getAuthToken())
            .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
            .build();
    }

    private HttpRequest buildMaasSpeechSynthesizerRequest(AiSpeechProvider provider, TtsGenerateRequest request, String endpoint) {
        Dict config = remarkConfig(provider);
        Map<String, Object> input = new LinkedHashMap<>();
        input.putAll(mapAt(config, "input"));
        input.put("text", request.text());
        input.put("voice", chooseVoice(provider, request, false, config));
        input.put("format", format(request));
        input.put("sample_rate", sampleRate(request));
        input.putAll(mapAt(config, "parameters"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model(provider, false, config));
        payload.put("input", input);

        String payloadJson = JsonUtils.toJsonString(payload);
        log.info("Call Aliyun MaaS SpeechSynthesizer TTS, providerCode={}, model={}, voice={}, format={}, sampleRate={}, endpoint={}",
            provider.getProviderCode(), payload.get("model"), input.get("voice"), input.get("format"), input.get("sample_rate"), endpoint);
        log.debug("Aliyun MaaS SpeechSynthesizer TTS request body={}", payloadJson);

        return HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getAuthToken())
            .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
            .build();
    }

    private HttpRequest buildGenerationRequest(AiSpeechProvider provider, TtsGenerateRequest request, String endpoint) {
        Dict config = remarkConfig(provider);
        Map<String, Object> input = new LinkedHashMap<>();
        input.putAll(mapAt(config, "input"));
        input.put("text", request.text());
        input.put("voice", chooseVoice(provider, request, false, config));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", format(request));
        parameters.put("sample_rate", sampleRate(request));
        parameters.putAll(mapAt(config, "parameters"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model(provider, false, config));
        payload.put("input", input);
        payload.put("parameters", parameters);

        String payloadJson = JsonUtils.toJsonString(payload);
        log.info("调用阿里云百炼 generation TTS，providerCode={}，model={}，voice={}，format={}，sampleRate={}，endpoint={}",
            provider.getProviderCode(), payload.get("model"), input.get("voice"), parameters.get("format"), parameters.get("sample_rate"), endpoint);
        log.debug("阿里云百炼 generation TTS 请求体={}", payloadJson);

        return HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getAuthToken())
            .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
            .build();
    }

    private TtsGenerateResult parseJsonResult(HttpClient client, AiSpeechProvider provider, byte[] body, String defaultFormat) {
        Dict dict = JsonUtils.parseObject(body, Dict.class);
        if (dict == null) {
            throw new ServiceException("阿里云百炼 TTS 返回空 JSON");
        }
        String errorCode = firstText(dict, List.of("code", "error.code"));
        String errorMessage = firstText(dict, List.of("message", "error.message"));
        if (StringUtils.isNotBlank(errorCode) || StringUtils.isNotBlank(errorMessage)) {
            throw new ServiceException("阿里云百炼 TTS 调用失败，code=" + errorCode + "，message=" + errorMessage);
        }
        String audioUrl = firstText(dict, List.of(
            "output.audio.url",
            "output.audio_url",
            "output.url",
            "audioUrl",
            "url"
        ));
        if (StringUtils.isNotBlank(audioUrl)) {
            return downloadAudio(client, provider, audioUrl, defaultFormat);
        }
        String audioBase64 = firstText(dict, List.of(
            "output.audio.data",
            "output.audio.base64",
            "output.audio",
            "audioData",
            "audio"
        ));
        if (StringUtils.isNotBlank(audioBase64)) {
            return new TtsGenerateResult(Base64.getDecoder().decode(stripDataUrlPrefix(audioBase64)),
                contentType(defaultFormat), suffix(null, defaultFormat), null);
        }
        throw new ServiceException("阿里云百炼 TTS 返回 JSON 中未找到音频地址或音频内容，响应=" + safeBody(body));
    }

    private TtsGenerateResult downloadAudio(HttpClient client, AiSpeechProvider provider, String audioUrl, String defaultFormat) {
        HttpResponse<byte[]> response = send(client, HttpRequest.newBuilder(URI.create(audioUrl))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .GET()
            .build());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("下载阿里云百炼 TTS 音频失败，HTTP状态码=" + response.statusCode());
        }
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(contentType(defaultFormat));
        return new TtsGenerateResult(response.body(), contentType, suffix(contentType, defaultFormat), null);
    }

    private HttpResponse<byte[]> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new ServiceException("请求阿里云百炼 TTS 失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("请求阿里云百炼 TTS 被中断");
        }
    }

    private String endpoint(AiSpeechProvider provider) {
        Dict config = remarkConfig(provider);
        String endpoint = StringUtils.isBlank(provider.getEndpointUrl()) ? DEFAULT_ENDPOINT_TEMPLATE : provider.getEndpointUrl().trim();
        String workspaceId = firstText(config, List.of("workspaceId", "workspace_id"));
        if (endpoint.contains("{workspaceId}") || endpoint.contains("{WorkspaceId}")) {
            if (StringUtils.isBlank(workspaceId)) {
                throw new ServiceException("阿里云百炼 TTS 地址包含 workspaceId 占位符，请在备注/扩展JSON中配置 workspaceId，或填写完整接口地址");
            }
            endpoint = endpoint.replace("{workspaceId}", workspaceId).replace("{WorkspaceId}", workspaceId);
        }
        return endpoint;
    }

    private boolean isOpenAiCompatibleEndpoint(String endpoint) {
        return endpoint.contains("/compatible-mode/v1") || endpoint.endsWith("/audio/speech");
    }

    private String speechEndpoint(String endpoint) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (normalized.endsWith("/audio/speech")) {
            return normalized;
        }
        if (normalized.endsWith("/compatible-mode/v1")) {
            return normalized + "/audio/speech";
        }
        return normalized;
    }

    private Dict remarkConfig(AiSpeechProvider provider) {
        return StringUtils.isBlank(provider.getRemark()) ? null : JsonUtils.parseObject(provider.getRemark(), Dict.class);
    }

    private String model(AiSpeechProvider provider, boolean openAiCompatible, Dict config) {
        String model = firstText(config, List.of("model"));
        if (StringUtils.isNotBlank(model)) {
            return model;
        }
        return openAiCompatible ? DEFAULT_OPENAI_MODEL : DEFAULT_GENERATION_MODEL;
    }

    private String chooseVoice(AiSpeechProvider provider, TtsGenerateRequest request, boolean openAiCompatible, Dict config) {
        if (StringUtils.isNotBlank(request.voice())) {
            return request.voice();
        }
        String remarkVoice = firstText(config, List.of("voice"));
        if (StringUtils.isNotBlank(remarkVoice)) {
            return remarkVoice;
        }
        if (StringUtils.isNotBlank(provider.getDefaultVoice())) {
            return provider.getDefaultVoice();
        }
        return openAiCompatible ? DEFAULT_OPENAI_VOICE : DEFAULT_GENERATION_VOICE;
    }

    private String format(TtsGenerateRequest request) {
        return StringUtils.isBlank(request.format()) ? "wav" : request.format().replace(".", "").toLowerCase();
    }

    private Integer sampleRate(TtsGenerateRequest request) {
        return request.sampleRate() == null || request.sampleRate() <= 0 ? 8000 : request.sampleRate();
    }

    private int timeout(AiSpeechProvider provider) {
        return provider.getTimeoutSeconds() == null || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds();
    }

    private String firstText(Dict dict, List<String> paths) {
        if (dict == null) {
            return null;
        }
        for (String path : paths) {
            Object value = valueAt(dict, path);
            if (value != null) {
                String text = String.valueOf(value);
                if (StringUtils.isNotBlank(text) && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object valueAt(Map<String, Object> source, String path) {
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAt(Dict dict, String path) {
        Object value = valueAt(dict, path);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null && item != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String stripDataUrlPrefix(String value) {
        int commaIndex = value.indexOf(',');
        return value.startsWith("data:") && commaIndex > -1 ? value.substring(commaIndex + 1) : value;
    }

    private String safeBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private String contentType(String defaultFormat) {
        String format = StringUtils.isBlank(defaultFormat) ? "wav" : defaultFormat.toLowerCase();
        return "mp3".equals(format) ? "audio/mpeg" : "audio/wav";
    }

    private String suffix(String contentType, String defaultFormat) {
        if (contentType != null) {
            if (contentType.contains("wav")) {
                return ".wav";
            }
            if (contentType.contains("mpeg") || contentType.contains("mp3")) {
                return ".mp3";
            }
            if (contentType.contains("ogg")) {
                return ".ogg";
            }
        }
        return "." + (StringUtils.isBlank(defaultFormat) ? "wav" : defaultFormat.replace(".", ""));
    }
}
