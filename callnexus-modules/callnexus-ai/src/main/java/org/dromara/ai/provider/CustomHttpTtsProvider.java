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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class CustomHttpTtsProvider implements TtsProvider {
    private static final String TYPE = "CUSTOM_HTTP";

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public TtsGenerateResult generate(AiSpeechProvider provider, TtsGenerateRequest request) {
        if (StringUtils.isBlank(provider.getEndpointUrl())) {
            throw new ServiceException("TTS Provider 请求地址不能为空");
        }
        if (provider.getEndpointUrl().contains("dashscope.aliyuncs.com")) {
            throw new ServiceException("当前请求地址是阿里云百炼 DashScope，请将服务商类型改为 ALIYUN_DASHSCOPE，不要使用通用HTTP类型直连阿里云");
        }
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout(provider)))
            .build();
        HttpRequest httpRequest = buildRequest(provider, request);
        HttpResponse<byte[]> response = send(client, httpRequest);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("TTS 服务响应失败，HTTP状态码=" + response.statusCode());
        }
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("application/octet-stream");
        byte[] body = response.body();
        if (contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return downloadFromJson(client, body, provider);
        }
        return new TtsGenerateResult(body, contentType, suffix(contentType, provider.getDefaultFormat()), null);
    }

    private HttpRequest buildRequest(AiSpeechProvider provider, TtsGenerateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", request.text());
        payload.put("voice", request.voice());
        payload.put("format", request.format());
        payload.put("sampleRate", request.sampleRate());
        payload.put("businessType", request.businessType());
        payload.put("metadata", request.metadata());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(provider.getEndpointUrl()))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJsonString(payload)));
        applyAuth(provider, builder);
        return builder.build();
    }

    private TtsGenerateResult downloadFromJson(HttpClient client, byte[] body, AiSpeechProvider provider) {
        Dict dict = JsonUtils.parseObject(body, Dict.class);
        String audioUrl = dict == null ? null : String.valueOf(dict.get("audioUrl", dict.get("url")));
        if (StringUtils.isBlank(audioUrl) || "null".equals(audioUrl)) {
            throw new ServiceException("TTS 服务返回 JSON 但未包含 audioUrl");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(audioUrl))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .GET();
        applyAuth(provider, builder);
        HttpResponse<byte[]> response = send(client, builder.build());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("下载 TTS 音频失败，HTTP状态码=" + response.statusCode());
        }
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("application/octet-stream");
        return new TtsGenerateResult(response.body(), contentType, suffix(contentType, provider.getDefaultFormat()), null);
    }

    private HttpResponse<byte[]> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new ServiceException("请求 TTS 服务失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("请求 TTS 服务被中断");
        }
    }

    private void applyAuth(AiSpeechProvider provider, HttpRequest.Builder builder) {
        if (StringUtils.isBlank(provider.getAuthType()) || "NONE".equalsIgnoreCase(provider.getAuthType())) {
            return;
        }
        if (StringUtils.isBlank(provider.getAuthToken())) {
            throw new ServiceException("TTS Provider 已配置认证方式但 Token 为空");
        }
        if ("BEARER".equalsIgnoreCase(provider.getAuthType())) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getAuthToken());
            return;
        }
        if ("HEADER".equalsIgnoreCase(provider.getAuthType())) {
            String headerName = StringUtils.isBlank(provider.getAuthHeaderName()) ? "X-Api-Key" : provider.getAuthHeaderName();
            builder.header(headerName, provider.getAuthToken());
        }
    }

    private int timeout(AiSpeechProvider provider) {
        return provider.getTimeoutSeconds() == null || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds();
    }

    private String suffix(String contentType, String defaultFormat) {
        if (contentType != null) {
            if (contentType.contains("wav")) return ".wav";
            if (contentType.contains("mpeg") || contentType.contains("mp3")) return ".mp3";
            if (contentType.contains("ogg")) return ".ogg";
        }
        return "." + (StringUtils.isBlank(defaultFormat) ? "wav" : defaultFormat.replace(".", ""));
    }
}
