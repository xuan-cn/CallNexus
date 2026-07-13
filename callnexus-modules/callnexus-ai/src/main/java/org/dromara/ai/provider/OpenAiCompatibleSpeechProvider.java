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
import java.math.BigDecimal;
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

/**
 * OpenAI compatible speech adapter.
 *
 * <p>This adapter intentionally only handles the stable HTTP-style OpenAI
 * speech APIs. Vendor-specific realtime protocols stay in their own adapters.</p>
 */
@Component
@Slf4j
public class OpenAiCompatibleSpeechProvider implements TtsProvider, AsrProvider {

    private static final String TYPE = "OPENAI_COMPATIBLE";
    private static final String DEFAULT_TTS_MODEL = "gpt-4o-mini-tts";
    private static final String DEFAULT_ASR_MODEL = "whisper-1";

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public TtsGenerateResult generate(AiSpeechProvider provider, TtsGenerateRequest request) {
        requireToken(provider);
        String endpoint = speechEndpoint(provider.getEndpointUrl(), "/audio/speech");
        Dict config = config(provider);
        String format = format(request.format(), provider.getDefaultFormat());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", firstText(config, "ttsModel", "model", DEFAULT_TTS_MODEL));
        payload.put("input", request.text());
        payload.put("voice", voice(provider, request, config));
        payload.put("response_format", format);
        payload.putAll(mapAt(config, "ttsParameters"));

        HttpClient client = client(provider);
        HttpResponse<byte[]> response = send(client, HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJsonString(payload), StandardCharsets.UTF_8))
            .build(), provider);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("OpenAI compatible TTS failed, status=" + response.statusCode()
                + ", response=" + safeBody(response.body()));
        }
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(contentType(format));
        if (contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
            throw new ServiceException("OpenAI compatible TTS returned JSON instead of audio, response="
                + safeBody(response.body()));
        }
        return new TtsGenerateResult(response.body(), contentType, suffix(contentType, format), null);
    }

    @Override
    public AsrTranscribeResult transcribe(AiSpeechProvider provider, AsrTranscribeRequest request) {
        requireToken(provider);
        if (request.audioBytes() == null || request.audioBytes().length == 0) {
            throw new ServiceException("ASR audio file is empty");
        }
        String endpoint = speechEndpoint(provider.getRecordingAsrEndpointUrl(), "/audio/transcriptions");
        Dict config = config(provider);
        String format = format(request.format(), provider.getAsrFormat());
        String boundary = "----callnexus-" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(boundary, Map.of(
            "model", firstText(config, "asrModel", DEFAULT_ASR_MODEL),
            "response_format", firstText(config, "asrResponseFormat", "json")
        ), "file", "audio." + format, request.audioBytes(), audioMimeType(format), mapAt(config, "asrParameters"));

        HttpResponse<String> response = sendString(client(provider), HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build(), provider);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("OpenAI compatible ASR failed, status=" + response.statusCode()
                + ", response=" + StringUtils.blankToDefault(response.body(), ""));
        }
        String text = parseAsrText(response.body());
        return new AsrTranscribeResult(text, List.of(new AsrSegment(0, null, null, text, null, true)));
    }

    private HttpClient client(AiSpeechProvider provider) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout(provider)))
            .build();
    }

    private HttpResponse<byte[]> send(HttpClient client, HttpRequest request, AiSpeechProvider provider) {
        try {
            return client.send(applyAuth(provider, request), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new ServiceException("OpenAI compatible speech request failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("OpenAI compatible speech request interrupted");
        }
    }

    private HttpResponse<String> sendString(HttpClient client, HttpRequest request, AiSpeechProvider provider) {
        try {
            return client.send(applyAuth(provider, request), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ServiceException("OpenAI compatible speech request failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("OpenAI compatible speech request interrupted");
        }
    }

    private HttpRequest applyAuth(AiSpeechProvider provider, HttpRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
            .timeout(request.timeout().orElse(Duration.ofSeconds(timeout(provider))))
            .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        request.headers().map().forEach((key, values) -> values.forEach(value -> builder.header(key, value)));
        if ("HEADER".equalsIgnoreCase(provider.getAuthType())) {
            builder.header(StringUtils.blankToDefault(provider.getAuthHeaderName(), "X-Api-Key"), provider.getAuthToken());
        } else {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getAuthToken());
        }
        return builder.build();
    }

    private void requireToken(AiSpeechProvider provider) {
        if (StringUtils.isBlank(provider.getAuthToken())) {
            throw new ServiceException("OpenAI compatible speech API key is empty");
        }
    }

    private String speechEndpoint(String configured, String path) {
        if (StringUtils.isBlank(configured)) {
            throw new ServiceException("OpenAI compatible speech endpoint is empty");
        }
        String endpoint = configured.trim();
        if (endpoint.endsWith(path)) {
            return endpoint;
        }
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (endpoint.endsWith("/v1") || endpoint.endsWith("/compatible-mode/v1")) {
            return endpoint + path;
        }
        return endpoint;
    }

    private Dict config(AiSpeechProvider provider) {
        if (StringUtils.isBlank(provider.getRemark())) {
            return null;
        }
        return JsonUtils.parseObject(provider.getRemark(), Dict.class);
    }

    private String firstText(Dict dict, String firstKey, String defaultValue) {
        return firstText(dict, firstKey, null, defaultValue);
    }

    private String firstText(Dict dict, String firstKey, String secondKey, String defaultValue) {
        String value = textAt(dict, firstKey);
        if (StringUtils.isBlank(value) && StringUtils.isNotBlank(secondKey)) {
            value = textAt(dict, secondKey);
        }
        return StringUtils.blankToDefault(value, defaultValue);
    }

    private String textAt(Dict dict, String key) {
        Object value = dict == null ? null : dict.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAt(Dict dict, String key) {
        Object value = dict == null ? null : dict.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((itemKey, itemValue) -> {
            if (itemKey != null && itemValue != null) {
                result.put(String.valueOf(itemKey), itemValue);
            }
        });
        return result;
    }

    private String voice(AiSpeechProvider provider, TtsGenerateRequest request, Dict config) {
        if (StringUtils.isNotBlank(request.voice())) {
            return request.voice();
        }
        String configured = textAt(config, "voice");
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }
        return StringUtils.blankToDefault(provider.getDefaultVoice(), "alloy");
    }

    private String format(String requestFormat, String providerFormat) {
        String value = StringUtils.blankToDefault(requestFormat, providerFormat);
        return StringUtils.blankToDefault(value, "wav").replace(".", "").toLowerCase();
    }

    private int timeout(AiSpeechProvider provider) {
        return provider.getTimeoutSeconds() == null || provider.getTimeoutSeconds() <= 0 ? 30 : provider.getTimeoutSeconds();
    }

    private String parseAsrText(String body) {
        Dict dict = JsonUtils.parseObject(body, Dict.class);
        String text = dict == null ? null : String.valueOf(dict.get("text"));
        if (StringUtils.isBlank(text) || "null".equalsIgnoreCase(text)) {
            throw new ServiceException("OpenAI compatible ASR returned no text, response=" + body);
        }
        return text;
    }

    private byte[] multipartBody(String boundary, Map<String, Object> fields, String fileField,
                                 String fileName, byte[] fileBytes, String contentType,
                                 Map<String, Object> extraFields) {
        StringBuilder head = new StringBuilder();
        Map<String, Object> allFields = new LinkedHashMap<>(fields);
        allFields.putAll(extraFields);
        allFields.forEach((key, value) -> {
            head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n");
        });
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(fileField)
            .append("\"; filename=\"").append(fileName).append("\"\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n\r\n");
        byte[] prefix = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + fileBytes.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(fileBytes, 0, result, prefix.length, fileBytes.length);
        System.arraycopy(suffix, 0, result, prefix.length + fileBytes.length, suffix.length);
        return result;
    }

    private String audioMimeType(String format) {
        return switch (format) {
            case "mp3", "mpeg" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "wav", "wave" -> "audio/wav";
            default -> "audio/" + format;
        };
    }

    private String contentType(String format) {
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
        return "." + StringUtils.blankToDefault(defaultFormat, "wav").replace(".", "");
    }

    private String safeBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
