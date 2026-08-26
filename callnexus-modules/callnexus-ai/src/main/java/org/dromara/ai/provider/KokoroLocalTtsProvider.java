package org.dromara.ai.provider;

import cn.hutool.core.lang.Dict;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local Kokoro FastAPI adapter for normal and streaming TTS. */
@Component
public class KokoroLocalTtsProvider implements TtsProvider, StreamingTtsProvider, TtsVoiceCatalogProvider {

    private static final String TYPE = "KOKORO_LOCAL";
    private static final int DEFAULT_SOURCE_SAMPLE_RATE = 24000;
    private final Executor executor;

    public KokoroLocalTtsProvider(@Qualifier("aiRealtimeExecutor") Executor executor) {
        this.executor = executor;
    }

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public TtsGenerateResult generate(AiSpeechProvider provider, TtsGenerateRequest request) {
        String format = normalizeFormat(request.format(), provider.getDefaultFormat());
        Map<String, Object> payload = payload(provider, request.text(), voice(provider, request.voice()), format, false);
        HttpRequest httpRequest = requestBuilder(provider, speechEndpoint(provider))
            .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJsonString(payload), StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<byte[]> response = client(provider).send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            requireSuccess(response.statusCode(), response.body());
            String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE)
                .orElse(contentType(format));
            return new TtsGenerateResult(response.body(), contentType, "." + format, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Kokoro TTS 调用被中断");
        } catch (IOException exception) {
            throw new ServiceException("Kokoro TTS 调用失败：" + exception.getMessage());
        }
    }

    @Override
    public StreamingTtsSession open(AiSpeechProvider provider, StreamingTtsRequest request,
                                    StreamingTtsListener listener) {
        return new KokoroStreamingSession(provider, request, listener);
    }

    @Override
    public List<String> voices(AiSpeechProvider provider) {
        HttpRequest request = requestBuilder(provider, voicesEndpoint(provider)).GET().build();
        try {
            HttpResponse<String> response = client(provider).send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException("读取 Kokoro 音色失败，HTTP状态码=" + response.statusCode()
                    + "，响应=" + safeBody(response.body()));
            }
            Dict body = JsonUtils.parseObject(response.body(), Dict.class);
            Object value = body == null ? null : body.get("voices");
            if (!(value instanceof List<?> list)) {
                throw new ServiceException("Kokoro 音色接口响应格式不正确");
            }
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("读取 Kokoro 音色被中断");
        } catch (IOException exception) {
            throw new ServiceException("读取 Kokoro 音色失败：" + exception.getMessage());
        }
    }

    private final class KokoroStreamingSession implements StreamingTtsSession {
        private final AiSpeechProvider provider;
        private final StreamingTtsRequest request;
        private final StreamingTtsListener listener;
        private final StringBuilder text = new StringBuilder();
        private final AtomicBoolean finishing = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile InputStream responseStream;

        private KokoroStreamingSession(AiSpeechProvider provider, StreamingTtsRequest request,
                                       StreamingTtsListener listener) {
            this.provider = provider;
            this.request = request;
            this.listener = listener;
        }

        @Override
        public synchronized void append(String value) {
            if (!closed.get() && StringUtils.isNotBlank(value)) {
                text.append(value);
            }
        }

        @Override
        public void commit() {
            // Kokoro receives one HTTP request per sentence; finish submits the buffered text.
        }

        @Override
        public void finish() {
            if (closed.get() || !finishing.compareAndSet(false, true)) {
                return;
            }
            CompletableFuture.runAsync(this::synthesize, executor);
        }

        private void synthesize() {
            String input;
            synchronized (this) {
                input = text.toString();
            }
            if (StringUtils.isBlank(input)) {
                fail("合成文本不能为空");
                return;
            }
            int targetSampleRate = request.sampleRate() == null || request.sampleRate() <= 0
                ? (provider.getDefaultSampleRate() == null ? 8000 : provider.getDefaultSampleRate())
                : request.sampleRate();
            int sourceSampleRate = intOption(provider, "sourceSampleRate", DEFAULT_SOURCE_SAMPLE_RATE);
            StreamingPcm16Resampler resampler = new StreamingPcm16Resampler(sourceSampleRate, targetSampleRate);
            Map<String, Object> payload = payload(provider, input, voice(provider, request.voice()), "pcm", true);
            HttpRequest httpRequest = requestBuilder(provider, speechEndpoint(provider))
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJsonString(payload), StandardCharsets.UTF_8))
                .build();
            try {
                HttpResponse<InputStream> response = client(provider).send(httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
                responseStream = response.body();
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    byte[] error = responseStream.readNBytes(2000);
                    requireSuccess(response.statusCode(), error);
                }
                if (closed.get()) {
                    return;
                }
                listener.onStarted();
                byte[] buffer = new byte[4096];
                int read;
                while (!closed.get() && (read = responseStream.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    byte[] converted = sourceSampleRate == targetSampleRate
                        ? copy(buffer, read) : resampler.accept(buffer, read);
                    if (converted.length > 0 && !closed.get()) {
                        listener.onAudio(converted);
                    }
                }
                if (!closed.get()) {
                    listener.onCompleted();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("Kokoro 实时 TTS 调用被中断");
            } catch (Exception exception) {
                fail("Kokoro 实时 TTS 调用失败：" + rootMessage(exception));
            } finally {
                closeStream();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeStream();
            }
        }

        private void fail(String message) {
            if (!closed.get()) {
                listener.onError(message);
            }
        }

        private void closeStream() {
            InputStream current = responseStream;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Closing an already completed HTTP body is harmless.
                }
            }
        }
    }

    private HttpClient client(AiSpeechProvider provider) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.min(timeout(provider), 10)))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    private HttpRequest.Builder requestBuilder(AiSpeechProvider provider, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.ACCEPT, "audio/*, application/json");
        applyAuthentication(builder, provider);
        return builder;
    }

    private void applyAuthentication(HttpRequest.Builder builder, AiSpeechProvider provider) {
        if (StringUtils.isBlank(provider.getAuthToken()) || "NONE".equalsIgnoreCase(provider.getAuthType())) {
            return;
        }
        if ("HEADER".equalsIgnoreCase(provider.getAuthType())) {
            if (StringUtils.isBlank(provider.getAuthHeaderName())) {
                throw new ServiceException("Kokoro Header 认证名称不能为空");
            }
            builder.header(provider.getAuthHeaderName().trim(), provider.getAuthToken());
        } else {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getAuthToken());
        }
    }

    private Map<String, Object> payload(AiSpeechProvider provider, String text, String voice,
                                        String format, boolean stream) {
        Dict options = options(provider);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", textOption(options, "model", "kokoro"));
        payload.put("input", text);
        payload.put("voice", voice);
        payload.put("response_format", format);
        payload.put("speed", decimalOption(options, "speed", 1.0D));
        payload.put("stream", stream);
        payload.put("lang_code", textOption(options, "langCode", "z"));
        payload.put("volume_multiplier", decimalOption(options, "volumeMultiplier", 1.0D));
        Object extra = options == null ? null : options.get("ttsParameters");
        if (extra instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    payload.put(String.valueOf(key), value);
                }
            });
        }
        return payload;
    }

    private URI speechEndpoint(AiSpeechProvider provider) {
        return endpoint(provider.getEndpointUrl(), "/v1/audio/speech");
    }

    private URI voicesEndpoint(AiSpeechProvider provider) {
        String configured = provider.getEndpointUrl();
        if (StringUtils.isBlank(configured)) {
            throw new ServiceException("Kokoro 服务地址不能为空");
        }
        String base = configured.trim().replaceAll("/+$", "")
            .replaceFirst("/v1/audio/speech$", "");
        return endpoint(base, "/v1/audio/voices");
    }

    private URI endpoint(String configured, String suffix) {
        if (StringUtils.isBlank(configured)) {
            throw new ServiceException("Kokoro 服务地址不能为空");
        }
        String value = configured.trim().replaceAll("/+$", "");
        if (!value.endsWith(suffix)) {
            value += suffix;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("Kokoro 服务地址格式错误：" + configured);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ServiceException("Kokoro 服务地址必须以 http:// 或 https:// 开头");
        }
        return uri;
    }

    private Dict options(AiSpeechProvider provider) {
        if (StringUtils.isBlank(provider.getRemark())) {
            return null;
        }
        try {
            return JsonUtils.parseObject(provider.getRemark(), Dict.class);
        } catch (Exception exception) {
            throw new ServiceException("Kokoro 扩展配置不是有效 JSON");
        }
    }

    private String voice(AiSpeechProvider provider, String requested) {
        if (StringUtils.isNotBlank(requested) && !"default".equalsIgnoreCase(requested.trim())) {
            return requested.trim();
        }
        return StringUtils.blankToDefault(provider.getTtsVoice(), "zf_001");
    }

    private String normalizeFormat(String requested, String configured) {
        String value = StringUtils.blankToDefault(requested, configured);
        value = StringUtils.blankToDefault(value, "wav").replace(".", "").toLowerCase();
        if (!List.of("mp3", "opus", "aac", "flac", "wav", "pcm").contains(value)) {
            throw new ServiceException("Kokoro 不支持音频格式：" + value);
        }
        return value;
    }

    private int timeout(AiSpeechProvider provider) {
        return provider.getTimeoutSeconds() == null || provider.getTimeoutSeconds() <= 0
            ? 60 : provider.getTimeoutSeconds();
    }

    private int intOption(AiSpeechProvider provider, String key, int defaultValue) {
        Dict config = options(provider);
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String textOption(Dict options, String key, String defaultValue) {
        Object value = options == null ? null : options.get(key);
        return value == null || StringUtils.isBlank(String.valueOf(value)) ? defaultValue : String.valueOf(value);
    }

    private double decimalOption(Dict options, String key, double defaultValue) {
        Object value = options == null ? null : options.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private void requireSuccess(int statusCode, byte[] body) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new ServiceException("Kokoro TTS 调用失败，HTTP状态码=" + statusCode
                + "，响应=" + safeBody(new String(body, StandardCharsets.UTF_8)));
        }
        if (body == null || body.length == 0) {
            throw new ServiceException("Kokoro TTS 未返回音频内容");
        }
    }

    private String contentType(String format) {
        return switch (format) {
            case "mp3" -> "audio/mpeg";
            case "opus" -> "audio/opus";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            default -> "application/octet-stream";
        };
    }

    private byte[] copy(byte[] source, int length) {
        byte[] result = new byte[length];
        System.arraycopy(source, 0, result, 0, length);
        return result;
    }

    private String safeBody(String body) {
        String value = StringUtils.blankToDefault(body, "");
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }
}
