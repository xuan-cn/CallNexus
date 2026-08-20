package org.dromara.ai.provider;

import cn.hutool.core.lang.Dict;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client for OpenAI-compatible FunASR transcription endpoints. */
@Component
public class FunAsrHttpClient {

    private final Map<Integer, HttpClient> clients = new ConcurrentHashMap<>();

    public AsrTranscribeResult transcribe(AiSpeechProvider provider, byte[] waveBytes,
                                          String fileName, String model,
                                          Map<String, Object> parameters) {
        String boundary = "----callnexus-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("model", StringUtils.blankToDefault(model, "sensevoice"));
        if (parameters != null) {
            fields.putAll(parameters);
        }
        byte[] body = multipartBody(boundary, fields, fileName, waveBytes);
        int timeoutSeconds = timeout(provider);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(provider.getRecordingAsrEndpointUrl()))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        applyAuthentication(request, provider);

        HttpResponse<String> response;
        try {
            response = client(timeoutSeconds).send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ServiceException("FunASR HTTP 调用失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("FunASR HTTP 调用被中断");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("FunASR HTTP 调用失败，状态码=" + response.statusCode()
                + "，响应=" + safeBody(response.body()));
        }
        String text = parseText(response.body());
        return new AsrTranscribeResult(text,
            List.of(new AsrSegment(0, null, null, text, null, true)));
    }

    private HttpClient client(int timeoutSeconds) {
        return clients.computeIfAbsent(timeoutSeconds, timeout -> HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.min(timeout, 10)))
            .version(HttpClient.Version.HTTP_1_1)
            .build());
    }

    private URI endpoint(String configured) {
        if (StringUtils.isBlank(configured)) {
            throw new ServiceException("FunASR HTTP 地址不能为空");
        }
        String endpoint = configured.trim().replaceAll("/+$", "");
        if (endpoint.endsWith("/v1")) {
            endpoint += "/audio/transcriptions";
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("FunASR HTTP 地址格式错误：" + configured);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ServiceException("FunASR 地址必须以 http:// 或 https:// 开头");
        }
        return uri;
    }

    private void applyAuthentication(HttpRequest.Builder request, AiSpeechProvider provider) {
        if (StringUtils.isBlank(provider.getAuthToken()) || "NONE".equalsIgnoreCase(provider.getAuthType())) {
            return;
        }
        if ("HEADER".equalsIgnoreCase(provider.getAuthType())) {
            if (StringUtils.isBlank(provider.getAuthHeaderName())) {
                throw new ServiceException("FunASR Header 认证名称不能为空");
            }
            request.header(provider.getAuthHeaderName().trim(), provider.getAuthToken());
            return;
        }
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getAuthToken());
    }

    private byte[] multipartBody(String boundary, Map<String, Object> fields,
                                 String fileName, byte[] fileBytes) {
        StringBuilder head = new StringBuilder();
        fields.forEach((key, value) -> head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
            .append(value).append("\r\n"));
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            .append(fileName).append("\"\r\n")
            .append("Content-Type: audio/wav\r\n\r\n");
        byte[] prefix = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + fileBytes.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(fileBytes, 0, body, prefix.length, fileBytes.length);
        System.arraycopy(suffix, 0, body, prefix.length + fileBytes.length, suffix.length);
        return body;
    }

    private String parseText(String body) {
        Dict response;
        try {
            response = JsonUtils.parseObject(body, Dict.class);
        } catch (Exception exception) {
            throw new ServiceException("FunASR 响应不是有效 JSON：" + safeBody(body));
        }
        Object value = response == null ? null : response.get("text");
        String text = value == null ? null : String.valueOf(value).trim();
        if (StringUtils.isBlank(text)) {
            throw new ServiceException("FunASR 未返回识别文本，响应=" + safeBody(body));
        }
        return text;
    }

    private int timeout(AiSpeechProvider provider) {
        return provider.getTimeoutSeconds() == null || provider.getTimeoutSeconds() <= 0
            ? 60 : provider.getTimeoutSeconds();
    }

    private String safeBody(String body) {
        String value = StringUtils.blankToDefault(body, "");
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
