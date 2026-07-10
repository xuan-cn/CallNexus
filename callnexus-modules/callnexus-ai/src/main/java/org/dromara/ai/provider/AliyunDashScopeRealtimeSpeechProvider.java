package org.dromara.ai.provider;

import cn.hutool.core.lang.Dict;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Alibaba Bailian Qwen realtime speech adapter.
 *
 * <p>It deliberately shares providerType with the existing DashScope TTS adapter
 * so one provider configuration can expose HTTP TTS, recording ASR, streaming
 * ASR and streaming TTS capabilities.</p>
 */
@Component
@Slf4j
public class AliyunDashScopeRealtimeSpeechProvider
    implements AsrProvider, StreamingAsrProvider, StreamingTtsProvider {

    private static final String TYPE = "ALIYUN_DASHSCOPE";
    private static final String DEFAULT_REALTIME_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
    private static final String DEFAULT_ASR_ENDPOINT_TEMPLATE = "https://{workspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_FILE_ASR_MODEL = "qwen3-asr-flash";
    private static final String DEFAULT_ASR_MODEL = "qwen3-asr-flash-realtime";
    private static final String DEFAULT_TTS_MODEL = "qwen3-tts-flash-realtime";

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public AsrTranscribeResult transcribe(AiSpeechProvider provider, AsrTranscribeRequest request) {
        if (request.audioBytes() == null || request.audioBytes().length == 0) {
            throw new ServiceException("百炼 ASR 测试音频不能为空");
        }
        requireApiKey(provider);
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout(provider)))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        String payload = JsonUtils.toJsonString(buildFileAsrPayload(provider, request));
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(fileAsrEndpoint(provider)))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header("Authorization", "Bearer " + provider.getAuthToken())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("百炼 ASR HTTP 调用被中断");
        } catch (Exception exception) {
            throw new ServiceException("百炼 ASR HTTP 调用失败：" + rootMessage(exception));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("百炼 ASR HTTP 调用失败，HTTP状态码=" + response.statusCode()
                + "，响应=" + StringUtils.blankToDefault(response.body(), ""));
        }
        return parseFileAsrResponse(response.body());
    }

    @Override
    public StreamingAsrSession open(AiSpeechProvider provider, StreamingAsrRequest request,
                                    StreamingAsrListener listener) {
        requireApiKey(provider);
        DashScopeAsrSession session = new DashScopeAsrSession(provider, request, listener);
        session.connect();
        return session;
    }

    @Override
    public StreamingTtsSession open(AiSpeechProvider provider, StreamingTtsRequest request,
                                    StreamingTtsListener listener) {
        requireApiKey(provider);
        DashScopeTtsSession session = new DashScopeTtsSession(provider, request, listener);
        session.connect();
        return session;
    }

    private abstract static class AbstractDashScopeSession implements WebSocket.Listener {
        protected final AiSpeechProvider provider;
        protected final CountDownLatch ready = new CountDownLatch(1);
        protected final AtomicBoolean closed = new AtomicBoolean();
        private final StringBuilder textBuffer = new StringBuilder();
        private final Object sendLock = new Object();
        private CompletableFuture<Void> outbound = CompletableFuture.completedFuture(null);
        protected volatile WebSocket socket;

        private AbstractDashScopeSession(AiSpeechProvider provider) {
            this.provider = provider;
        }

        protected void connect(String endpoint, String model) {
            try {
                WebSocket.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout(provider)))
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout(provider)))
                    .header("Authorization", "Bearer " + provider.getAuthToken())
                    .header("OpenAI-Beta", "realtime=v1");
                String workspaceId = option(provider.getRemark(), "workspaceId");
                if (StringUtils.isNotBlank(workspaceId)) {
                    builder.header("X-DashScope-WorkSpace", workspaceId);
                }
                socket = builder.buildAsync(URI.create(withModel(endpoint, model)), this).join();
                if (!ready.await(timeout(provider), TimeUnit.SECONDS)) {
                    throw new ServiceException("连接百炼实时语音服务超时");
                }
            } catch (ServiceException exception) {
                closeSocket();
                throw exception;
            } catch (Exception exception) {
                closeSocket();
                throw new ServiceException("连接百炼实时语音服务失败：" + rootMessage(exception));
            }
        }

        protected void sendEvent(Map<String, Object> event) {
            event.putIfAbsent("event_id", "event_" + UUID.randomUUID().toString().replace("-", ""));
            sendText(JsonUtils.toJsonString(event));
        }

        protected void sendText(String payload) {
            synchronized (sendLock) {
                outbound = outbound.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> socket.sendText(payload, true))
                    .thenAccept(ignored -> { });
            }
        }

        protected void closeSocket() {
            if (closed.compareAndSet(false, true) && socket != null) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "completed");
                } catch (Exception ignored) {
                    socket.abort();
                }
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            webSocket.request(1);
            onConnected();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (last) {
                    String payload = textBuffer.toString();
                    textBuffer.setLength(0);
                    try {
                        handle(JsonUtils.getObjectMapper().readTree(payload));
                    } catch (Exception exception) {
                        fail("解析百炼实时语音事件失败：" + exception.getMessage());
                    }
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.set(true);
            ready.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail("百炼实时语音连接异常：" + rootMessage(error));
            ready.countDown();
        }

        protected abstract void onConnected();

        protected abstract void handle(JsonNode event);

        protected abstract void fail(String message);
    }

    private static final class DashScopeAsrSession extends AbstractDashScopeSession implements StreamingAsrSession {
        private final StreamingAsrRequest request;
        private final StreamingAsrListener listener;
        private final List<AsrSegment> segments = new ArrayList<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicBoolean finishing = new AtomicBoolean();

        private DashScopeAsrSession(AiSpeechProvider provider, StreamingAsrRequest request,
                                    StreamingAsrListener listener) {
            super(provider);
            this.request = request;
            this.listener = listener;
        }

        private void connect() {
            String endpoint = StringUtils.blankToDefault(provider.getStreamingAsrEndpointUrl(), DEFAULT_REALTIME_ENDPOINT);
            String model = StringUtils.blankToDefault(option(provider.getAsrOptionsJson(), "model"), DEFAULT_ASR_MODEL);
            if (!model.toLowerCase().contains("realtime")) {
                throw new ServiceException("百炼实时 ASR 需要使用 realtime 模型，请将 ASR扩展参数 model 配置为 qwen3-asr-flash-realtime");
            }
            connect(endpoint, model);
            log.info("已连接百炼实时 ASR，providerCode={}，model={}，sampleRate={}",
                provider.getProviderCode(), model, sampleRate(provider, request.sampleRate()));
        }

        @Override
        protected void onConnected() {
            Map<String, Object> transcription = new LinkedHashMap<>();
            transcription.put("language", language(request.language()));
            String corpus = option(provider.getAsrOptionsJson(), "corpus");
            if (StringUtils.isNotBlank(corpus)) {
                transcription.put("corpus", Map.of("text", corpus));
            }
            Map<String, Object> turnDetection = new LinkedHashMap<>();
            turnDetection.put("type", "server_vad");
            turnDetection.put("threshold", decimalOption(provider.getAsrOptionsJson(), "vadThreshold", 0.0D));
            turnDetection.put("prefix_padding_ms", integerOption(provider.getAsrOptionsJson(), "prefixPaddingMs", 300));
            turnDetection.put("silence_duration_ms", provider.getAsrSilenceTimeoutMs() == null
                ? 800 : provider.getAsrSilenceTimeoutMs());
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("input_audio_format", "opus".equalsIgnoreCase(request.format()) ? "opus" : "pcm");
            session.put("sample_rate", sampleRate(provider, request.sampleRate()));
            session.put("input_audio_transcription", transcription);
            session.put("turn_detection", turnDetection);
            sendEvent(new LinkedHashMap<>(Map.of("type", "session.update", "session", session)));
        }

        @Override
        protected void handle(JsonNode event) {
            String type = event.path("type").asText();
            if (type.startsWith("session.") || type.startsWith("conversation.item.input_audio_transcription")) {
                log.info("收到百炼实时 ASR 事件，providerCode={}，type={}", provider.getProviderCode(), type);
            }
            switch (type) {
                case "session.updated" -> ready.countDown();
                case "conversation.item.input_audio_transcription.text" -> {
                    if (Boolean.TRUE.equals(provider.getAsrEnableIntermediateResult())) {
                        String text = event.path("text").asText("") + event.path("stash").asText("");
                        if (StringUtils.isNotBlank(text)) {
                            listener.onResult(new AsrSegment(sequence.get() + 1, null, null, text, null, false));
                        }
                    }
                }
                case "conversation.item.input_audio_transcription.completed" -> {
                    String text = event.path("transcript").asText("").trim();
                    if (StringUtils.isNotBlank(text)) {
                        AsrSegment segment = new AsrSegment(sequence.incrementAndGet(), null, null, text, null, true);
                        synchronized (segments) {
                            segments.add(segment);
                        }
                        listener.onResult(segment);
                    }
                }
                case "session.finished" -> {
                    List<AsrSegment> result;
                    synchronized (segments) {
                        result = List.copyOf(segments);
                    }
                    listener.onCompleted(new AsrTranscribeResult(
                        String.join("\n", result.stream().map(AsrSegment::text).toList()), result));
                    closeSocket();
                }
                case "conversation.item.input_audio_transcription.failed", "error" ->
                    fail(errorMessage(event));
                default -> log.trace("忽略百炼实时 ASR 事件，type={}", type);
            }
        }

        @Override
        public void send(byte[] audioBytes) {
            if (!closed.get() && audioBytes != null && audioBytes.length > 0) {
                sendEvent(new LinkedHashMap<>(Map.of(
                    "type", "input_audio_buffer.append",
                    "audio", Base64.getEncoder().encodeToString(audioBytes))));
            }
        }

        @Override
        public void finish() {
            if (!closed.get() && finishing.compareAndSet(false, true)) {
                sendEvent(new LinkedHashMap<>(Map.of("type", "input_audio_buffer.commit")));
                sendEvent(new LinkedHashMap<>(Map.of("type", "session.finish")));
            }
        }

        @Override
        public void close() {
            closeSocket();
        }

        @Override
        protected void fail(String message) {
            listener.onError("百炼实时 ASR 失败：" + message);
        }
    }

    private static final class DashScopeTtsSession extends AbstractDashScopeSession implements StreamingTtsSession {
        private final StreamingTtsRequest request;
        private final StreamingTtsListener listener;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean finishing = new AtomicBoolean();

        private DashScopeTtsSession(AiSpeechProvider provider, StreamingTtsRequest request,
                                    StreamingTtsListener listener) {
            super(provider);
            this.request = request;
            this.listener = listener;
        }

        private void connect() {
            String endpoint = StringUtils.blankToDefault(provider.getStreamingTtsEndpointUrl(), DEFAULT_REALTIME_ENDPOINT);
            String model = option(provider.getStreamingTtsOptionsJson(), "model");
            connect(endpoint, StringUtils.blankToDefault(model, DEFAULT_TTS_MODEL));
            log.info("已连接百炼实时 TTS，providerCode={}，model={}，voice={}，sampleRate={}",
                provider.getProviderCode(), StringUtils.blankToDefault(model, DEFAULT_TTS_MODEL), voice(), sampleRate());
        }

        @Override
        protected void onConnected() {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("voice", voice());
            session.put("mode", "commit");
            session.put("language_type", "Chinese");
            session.put("response_format", StringUtils.blankToDefault(request.format(), "pcm"));
            session.put("sample_rate", sampleRate());
            applyTtsOptions(session, provider.getStreamingTtsOptionsJson());
            sendEvent(new LinkedHashMap<>(Map.of("type", "session.update", "session", session)));
        }

        @Override
        protected void handle(JsonNode event) {
            String type = event.path("type").asText();
            switch (type) {
                case "session.updated" -> ready.countDown();
                case "response.created" -> {
                    if (started.compareAndSet(false, true)) {
                        listener.onStarted();
                    }
                }
                case "response.audio.delta" -> {
                    String audio = event.path("delta").asText(event.path("audio").asText(""));
                    if (StringUtils.isNotBlank(audio)) {
                        listener.onAudio(Base64.getDecoder().decode(audio));
                    }
                }
                case "response.audio.done" -> {
                    started.set(false);
                    listener.onCompleted();
                }
                case "session.finished" -> closeSocket();
                case "error" -> fail(errorMessage(event));
                default -> log.trace("忽略百炼实时 TTS 事件，type={}", type);
            }
        }

        @Override
        public void append(String text) {
            if (!closed.get() && StringUtils.isNotBlank(text)) {
                sendEvent(new LinkedHashMap<>(Map.of("type", "input_text_buffer.append", "text", text)));
            }
        }

        @Override
        public void commit() {
            if (!closed.get()) {
                sendEvent(new LinkedHashMap<>(Map.of("type", "input_text_buffer.commit")));
            }
        }

        @Override
        public void finish() {
            if (!closed.get() && finishing.compareAndSet(false, true)) {
                sendEvent(new LinkedHashMap<>(Map.of("type", "session.finish")));
            }
        }

        @Override
        public void close() {
            closeSocket();
        }

        @Override
        protected void fail(String message) {
            listener.onError("百炼实时 TTS 失败：" + message);
        }

        private String voice() {
            String requestVoice = request.voice();
            if (StringUtils.isNotBlank(requestVoice) && !"default".equalsIgnoreCase(requestVoice.trim())) {
                return requestVoice.trim();
            }
            return StringUtils.blankToDefault(provider.getDefaultVoice(), "Cherry");
        }

        private int sampleRate() {
            return request.sampleRate() == null || request.sampleRate() <= 0
                ? (provider.getDefaultSampleRate() == null ? 8000 : provider.getDefaultSampleRate())
                : request.sampleRate();
        }
    }

    private static void applyTtsOptions(Map<String, Object> session, String json) {
        Dict options = dict(json);
        if (options == null) {
            return;
        }
        copy(options, session, "speech_rate", "volume", "pitch_rate", "instructions", "optimize_instructions");
    }

    private static Map<String, Object> buildFileAsrPayload(AiSpeechProvider provider, AsrTranscribeRequest request) {
        String format = StringUtils.blankToDefault(request.format(), provider.getAsrFormat());
        Map<String, Object> inputAudio = new LinkedHashMap<>();
        inputAudio.put("data", audioDataUrl(request.audioBytes(), format));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "input_audio");
        content.put("input_audio", inputAudio);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(content));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", StringUtils.blankToDefault(option(provider.getAsrOptionsJson(), "model"), DEFAULT_FILE_ASR_MODEL));
        payload.put("messages", List.of(message));
        payload.put("stream", false);

        Map<String, Object> asrOptions = asrOptions(provider);
        if (!asrOptions.isEmpty()) {
            payload.put("asr_options", asrOptions);
        }
        return payload;
    }

    private static Map<String, Object> asrOptions(AiSpeechProvider provider) {
        Map<String, Object> result = new LinkedHashMap<>();
        Dict options = dict(provider.getAsrOptionsJson());
        if (options != null) {
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                if (!"model".equals(entry.getKey()) && entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        String language = language(provider.getAsrLanguage());
        if (StringUtils.isNotBlank(language)) {
            result.putIfAbsent("language", language);
        }
        if (provider.getAsrEnableItn() != null) {
            result.putIfAbsent("enable_itn", provider.getAsrEnableItn());
        }
        return result;
    }

    private static String fileAsrEndpoint(AiSpeechProvider provider) {
        String endpoint = StringUtils.blankToDefault(provider.getRecordingAsrEndpointUrl(), DEFAULT_ASR_ENDPOINT_TEMPLATE).trim();
        String workspaceId = option(provider.getRemark(), "workspaceId");
        if (StringUtils.isBlank(workspaceId)) {
            workspaceId = option(provider.getRemark(), "workspace_id");
        }
        if (endpoint.contains("{workspaceId}") || endpoint.contains("{WorkspaceId}")) {
            if (StringUtils.isBlank(workspaceId)) {
                throw new ServiceException("百炼 ASR 地址包含 workspaceId 占位符，请在备注/扩展JSON中配置 workspaceId，或填写完整接口地址");
            }
            endpoint = endpoint.replace("{workspaceId}", workspaceId).replace("{WorkspaceId}", workspaceId);
        }
        if (endpoint.endsWith("/compatible-mode/v1")) {
            return endpoint + "/chat/completions";
        }
        if (endpoint.endsWith("/compatible-mode/v1/")) {
            return endpoint + "chat/completions";
        }
        return endpoint;
    }

    private static String audioDataUrl(byte[] audioBytes, String format) {
        String normalized = StringUtils.blankToDefault(format, "wav").trim().toLowerCase();
        return "data:" + audioMimeType(normalized) + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
    }

    private static String audioMimeType(String format) {
        return switch (format) {
            case "mp3", "mpeg" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "wav", "wave" -> "audio/wav";
            default -> "audio/" + format;
        };
    }

    private static AsrTranscribeResult parseFileAsrResponse(String body) {
        JsonNode root;
        try {
            root = JsonUtils.getObjectMapper().readTree(body);
        } catch (Exception exception) {
            throw new ServiceException("解析百炼 ASR 响应失败：" + exception.getMessage());
        }
        String error = root.path("error").path("message").asText("");
        if (StringUtils.isBlank(error)) {
            error = root.path("message").asText("");
        }
        String code = root.path("code").asText(root.path("error").path("code").asText(""));
        if (StringUtils.isNotBlank(code) || root.has("error")) {
            throw new ServiceException("百炼 ASR 返回错误，code=" + code + "，message=" + error);
        }
        JsonNode choices = root.path("choices");
        String text = "";
        if (choices.isArray() && !choices.isEmpty()) {
            text = choices.get(0).path("message").path("content").asText("").trim();
        }
        if (StringUtils.isBlank(text)) {
            throw new ServiceException("百炼 ASR 未返回识别文本，响应=" + body);
        }
        return new AsrTranscribeResult(text, List.of(new AsrSegment(0, null, null, text, null, true)));
    }

    private static void copy(Dict source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                target.put(key, source.get(key));
            }
        }
    }

    private static String errorMessage(JsonNode event) {
        String message = event.path("error").path("message").asText("");
        if (StringUtils.isBlank(message)) {
            message = event.path("message").asText(event.toString());
        }
        return message;
    }

    private static String withModel(String endpoint, String model) {
        String value = endpoint.trim();
        if (value.contains("model=")) {
            return value;
        }
        return value + (value.contains("?") ? "&" : "?") + "model=" + model;
    }

    private static int timeout(AiSpeechProvider provider) {
        return provider.getTimeoutSeconds() == null ? 30 : Math.max(5, provider.getTimeoutSeconds());
    }

    private static int sampleRate(AiSpeechProvider provider, Integer requested) {
        if (requested != null && requested > 0) {
            return requested;
        }
        return provider.getAsrSampleRate() == null ? 8000 : provider.getAsrSampleRate();
    }

    private static String language(String value) {
        String language = StringUtils.blankToDefault(value, "zh").trim().toLowerCase();
        return language.startsWith("zh") ? "zh" : language;
    }

    private static void requireApiKey(AiSpeechProvider provider) {
        if (StringUtils.isBlank(provider.getAuthToken())) {
            throw new ServiceException("阿里云百炼 API Key 不能为空");
        }
    }

    private static String option(String json, String key) {
        Dict dict = dict(json);
        Object value = dict == null ? null : dict.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int integerOption(String json, String key, int defaultValue) {
        String value = option(json, key);
        try {
            return StringUtils.isBlank(value) ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static double decimalOption(String json, String key, double defaultValue) {
        String value = option(json, key);
        try {
            return StringUtils.isBlank(value) ? defaultValue : Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Dict dict(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(json, Dict.class);
        } catch (Exception exception) {
            throw new ServiceException("百炼实时语音扩展参数不是合法 JSON");
        }
    }

    private static WavAudio wavPcm(byte[] wav) {
        if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            throw new ServiceException("WAV 文件头不合法");
        }
        int channels = Short.toUnsignedInt(ByteBuffer.wrap(wav, 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
        int sampleRate = ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int bitsPerSample = Short.toUnsignedInt(ByteBuffer.wrap(wav, 34, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
        if (channels != 1) {
            throw new ServiceException("百炼实时 ASR 测试仅支持单声道 WAV");
        }
        if (bitsPerSample != 16) {
            throw new ServiceException("百炼实时 ASR 测试仅支持 16bit PCM WAV");
        }
        if (sampleRate <= 0) {
            throw new ServiceException("WAV 文件采样率不合法");
        }
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String chunk = new String(wav, offset, 4, StandardCharsets.US_ASCII);
            int length = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int dataOffset = offset + 8;
            if ("data".equals(chunk)) {
                int size = Math.min(Math.max(length, 0), wav.length - dataOffset);
                byte[] pcm = new byte[size];
                System.arraycopy(wav, dataOffset, pcm, 0, size);
                return new WavAudio(pcm, sampleRate);
            }
            offset = dataOffset + Math.max(length, 0) + (length & 1);
        }
        throw new ServiceException("WAV 文件中未找到 PCM 数据块");
    }

    private record WavAudio(byte[] pcm, int sampleRate) {
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }
}
