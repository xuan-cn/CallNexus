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
import java.util.Locale;
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
    private static final String DEFAULT_QWEN_AUDIO_ASR_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String QWEN_AUDIO_ASR_ENDPOINT_TEMPLATE = "https://{workspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DEFAULT_FILE_ASR_MODEL = "qwen3-asr-flash";
    private static final String DEFAULT_REALTIME_ASR_MODEL = "qwen3-asr-flash-realtime";
    private static final String DEFAULT_TTS_MODEL = "qwen3-tts-flash-realtime";
    // TLS/WebSocket setup can exceed five seconds under transient network or provider load.
    // Keep the provider timeout as the effective lower value, but allow a practical upper bound.
    private static final int REALTIME_CONNECT_TIMEOUT_SECONDS = 15;

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
        String model = fileAsrModel(provider);
        boolean qwenAudioAsr = isQwenAudioAsrModel(model);
        PreparedAudio preparedAudio = qwenAudioAsr ? prepareQwenAudio(request) : null;
        String payload = JsonUtils.toJsonString(qwenAudioAsr
            ? buildQwenAudioAsrPayload(provider, request, preparedAudio)
            : buildFileAsrPayload(provider, request));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(fileAsrEndpoint(provider, model)))
            .timeout(Duration.ofSeconds(timeout(provider)))
            .header("Authorization", "Bearer " + provider.getAuthToken())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (qwenAudioAsr) {
            requestBuilder.header("X-DashScope-SSE", "disable");
        }
        HttpRequest httpRequest = requestBuilder.build();
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
        return qwenAudioAsr ? parseQwenAudioAsrResponse(response.body()) : parseFileAsrResponse(response.body());
    }

    @Override
    public StreamingAsrSession open(AiSpeechProvider provider, StreamingAsrRequest request,
                                    StreamingAsrListener listener) {
        requireApiKey(provider);
        if ("qwen-audio-3.1-asr-flash-message".equals(realtimeAsrModel(provider))) {
            QwenMessageAsrSession session = new QwenMessageAsrSession(provider, request, listener);
            session.connect();
            return session;
        }
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
            connect(endpoint, model, true);
        }

        protected void connect(String endpoint, String model, boolean realtimeProtocol) {
            int connectTimeoutSeconds = Math.min(timeout(provider), REALTIME_CONNECT_TIMEOUT_SECONDS);
            try {
                WebSocket.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .header("Authorization", "Bearer " + provider.getAuthToken());
                if (realtimeProtocol) {
                    builder.header("OpenAI-Beta", "realtime=v1");
                }
                String workspaceId = option(provider.getCredentialJson(), "workspaceId");
                if (StringUtils.isNotBlank(workspaceId)) {
                    builder.header("X-DashScope-WorkSpace", workspaceId);
                }
                socket = builder.buildAsync(URI.create(realtimeProtocol ? withModel(endpoint, model) : endpoint), this).join();
                if (!ready.await(connectTimeoutSeconds, TimeUnit.SECONDS)) {
                    throw new ServiceException("连接百炼实时语音服务超时");
                }
                if (closed.get()) {
                    throw new ServiceException("百炼实时语音连接在初始化阶段已关闭");
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
            sendFrame(payload, null);
        }

        protected void sendBinary(byte[] audio) {
            sendFrame(null, audio.clone());
        }

        private void sendFrame(String payload, byte[] audio) {
            CompletableFuture<Void> current;
            synchronized (sendLock) {
                outbound = outbound.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> audio == null ? socket.sendText(payload, true)
                        : socket.sendBinary(ByteBuffer.wrap(audio), true))
                    .thenAccept(ignored -> { });
                current = outbound;
            }
            current.whenComplete((ignored, failure) -> {
                if (failure == null || !closed.compareAndSet(false, true)) {
                    return;
                }
                fail("发送百炼实时语音事件失败：" + rootMessage(failure));
                ready.countDown();
                if (socket != null) {
                    socket.abort();
                }
            });
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
            boolean expected = closed.getAndSet(true);
            ready.countDown();
            if (!expected) {
                fail("百炼实时语音连接意外关闭，code=" + statusCode + "，reason="
                    + StringUtils.blankToDefault(reason, "无"));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (closed.compareAndSet(false, true)) {
                fail("百炼实时语音连接异常：" + rootMessage(error));
            }
            ready.countDown();
            if (socket != null) {
                socket.abort();
            }
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
            String model = realtimeAsrModel(provider);
            if (!model.toLowerCase().contains("realtime")) {
                throw new ServiceException("百炼实时 ASR 需要使用 realtime 模型，请将 ASR扩展参数 realtimeModel 配置为 qwen3-asr-flash-realtime");
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

    private static final class QwenMessageAsrSession extends AbstractDashScopeSession implements StreamingAsrSession {
        private final StreamingAsrRequest request;
        private final StreamingAsrListener listener;
        private final String taskId = UUID.randomUUID().toString();
        private final List<AsrSegment> segments = new ArrayList<>();
        private final AtomicBoolean finishing = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private QwenMessageAsrSession(AiSpeechProvider provider, StreamingAsrRequest request,
                                      StreamingAsrListener listener) {
            super(provider);
            this.request = request;
            this.listener = listener;
        }

        private void connect() {
            if (sampleRate(provider, request.sampleRate()) != 16000) {
                throw new ServiceException("Qwen Audio ASR Message requires 16000 Hz audio");
            }
            connect(qwenMessageAsrEndpoint(provider), realtimeAsrModel(provider), false);
        }

        @Override
        protected void onConnected() {
            sendText(JsonUtils.toJsonString(buildQwenMessageRunTask(provider, request, taskId)));
        }

        @Override
        protected void handle(JsonNode event) {
            if (!taskId.equals(event.path("header").path("task_id").asText())) {
                return;
            }
            switch (event.path("header").path("event").asText()) {
                case "task-started" -> ready.countDown();
                case "result-generated" -> {
                    AsrSegment segment = parseQwenMessageSegment(event);
                    if (segment != null && !terminal.get()) {
                        if (segment.finalResult()) {
                            segments.add(segment);
                        }
                        if (segment.finalResult() || Boolean.TRUE.equals(provider.getAsrEnableIntermediateResult())) {
                            listener.onResult(segment);
                        }
                    }
                }
                case "task-finished" -> {
                    if (terminal.compareAndSet(false, true)) {
                        List<AsrSegment> result = List.copyOf(segments);
                        listener.onCompleted(new AsrTranscribeResult(
                            String.join("\n", result.stream().map(AsrSegment::text).toList()), result));
                    }
                    closeSocket();
                }
                case "task-failed" -> {
                    fail(event.path("header").path("error_code").asText() + ": "
                        + event.path("header").path("error_message").asText());
                    closeSocket();
                    ready.countDown();
                }
                default -> { }
            }
        }

        @Override
        public synchronized void send(byte[] audioBytes) {
            if (!closed.get() && !finishing.get() && audioBytes != null && audioBytes.length > 0) {
                sendBinary(audioBytes);
            }
        }

        @Override
        public synchronized void finish() {
            if (!closed.get() && finishing.compareAndSet(false, true)) {
                sendText(JsonUtils.toJsonString(Map.of("header", Map.of("action", "finish-task",
                    "task_id", taskId, "streaming", "duplex"), "payload", Map.of("input", Map.of()))));
            }
        }

        @Override
        public void close() {
            terminal.set(true);
            closeSocket();
        }

        @Override
        protected void fail(String message) {
            if (terminal.compareAndSet(false, true)) {
                listener.onError("Bailian ASR Message: " + message);
            }
        }
    }

    static Map<String, Object> buildQwenMessageRunTask(AiSpeechProvider provider,
                                                       StreamingAsrRequest request, String taskId) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", StringUtils.blankToDefault(request.format(), "pcm"));
        parameters.put("sample_rate", 16000);
        parameters.put("intermediate_result_enabled", Boolean.TRUE.equals(provider.getAsrEnableIntermediateResult()));
        parameters.put("heartbeat", true);
        parameters.put("max_sentence_silence", provider.getAsrSilenceTimeoutMs() == null ? 800
            : Math.max(200, Math.min(6000, provider.getAsrSilenceTimeoutMs())));
        Dict options = dict(provider.getAsrOptionsJson());
        for (String key : List.of("disfluency_removal_enabled", "keep_dialect", "vad_model",
            "vocabulary_id", "vocabulary", "language_hints", "speech_noise_threshold")) {
            if (options.containsKey(key)) {
                parameters.put(key, options.get(key));
            }
        }
        return Map.of("header", Map.of("action", "run-task", "task_id", taskId, "streaming", "duplex"),
            "payload", Map.of("task_group", "audio", "task", "asr", "function", "recognition",
                "model", realtimeAsrModel(provider), "parameters", parameters, "input", Map.of()));
    }

    static AsrSegment parseQwenMessageSegment(JsonNode event) {
        JsonNode sentence = event.path("payload").path("output").path("sentence");
        String text = sentence.path("text").asText("").trim();
        if (sentence.path("heartbeat").asBoolean() || text.isEmpty()) {
            return null;
        }
        return new AsrSegment(sentence.path("sentence_id").asInt(),
            sentence.path("begin_time").isNumber() ? sentence.path("begin_time").asInt() : null,
            sentence.path("end_time").isNumber() ? sentence.path("end_time").asInt() : null,
            text, null, sentence.path("sentence_end").asBoolean());
    }

    static String qwenMessageAsrEndpoint(AiSpeechProvider provider) {
        if ("CUSTOM".equalsIgnoreCase(provider.getStreamingAsrEndpointMode())
            && StringUtils.isNotBlank(provider.getStreamingAsrEndpointUrl())) {
            return provider.getStreamingAsrEndpointUrl().trim();
        }
        String workspace = option(provider.getCredentialJson(), "workspaceId");
        if (StringUtils.isBlank(workspace)) {
            workspace = option(provider.getCredentialJson(), "workspace_id");
        }
        return StringUtils.isBlank(workspace) ? "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
            : "wss://" + workspace + ".cn-beijing.maas.aliyuncs.com/api-ws/v1/inference";
    }

    private static final class DashScopeTtsSession extends AbstractDashScopeSession implements StreamingTtsSession {
        private final StreamingTtsRequest request;
        private final StreamingTtsListener listener;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean finishing = new AtomicBoolean();
        private final AtomicBoolean firstAudioReceived = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();

        private DashScopeTtsSession(AiSpeechProvider provider, StreamingTtsRequest request,
                                    StreamingTtsListener listener) {
            super(provider);
            this.request = request;
            this.listener = listener;
        }

        private void connect() {
            String endpoint = StringUtils.blankToDefault(provider.getStreamingTtsEndpointUrl(), DEFAULT_REALTIME_ENDPOINT);
            String model = provider.getStreamingTtsModel();
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
                case "session.updated" -> {
                    log.info("百炼实时 TTS 会话配置完成，providerCode={}", provider.getProviderCode());
                    ready.countDown();
                }
                case "input_text_buffer.committed" ->
                    log.info("百炼实时 TTS 文本已提交，providerCode={}", provider.getProviderCode());
                case "response.created" -> {
                    log.info("百炼实时 TTS 已创建响应，providerCode={}", provider.getProviderCode());
                    if (started.compareAndSet(false, true)) {
                        listener.onStarted();
                    }
                }
                case "response.audio.delta" -> {
                    String audio = event.path("delta").asText(event.path("audio").asText(""));
                    if (StringUtils.isNotBlank(audio)) {
                        if (firstAudioReceived.compareAndSet(false, true)) {
                            log.info("百炼实时 TTS 已收到首包音频，providerCode={}", provider.getProviderCode());
                        }
                        listener.onAudio(Base64.getDecoder().decode(audio));
                    }
                }
                case "response.audio.done", "response.done" -> complete(type);
                case "session.finished" -> {
                    log.info("百炼实时 TTS 会话已结束，providerCode={}，receivedAudio={}",
                        provider.getProviderCode(), firstAudioReceived.get());
                    closeSocket();
                }
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

        private void complete(String eventType) {
            log.info("百炼实时 TTS 响应完成，providerCode={}，eventType={}，receivedAudio={}",
                provider.getProviderCode(), eventType, firstAudioReceived.get());
            started.set(false);
            if (completed.compareAndSet(false, true)) {
                listener.onCompleted();
            }
        }

        private String voice() {
            String requestVoice = request.voice();
            if (StringUtils.isNotBlank(requestVoice) && !"default".equalsIgnoreCase(requestVoice.trim())) {
                return requestVoice.trim();
            }
            return StringUtils.blankToDefault(provider.getStreamingTtsVoice(), "Cherry");
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
        payload.put("model", fileAsrModel(provider));
        payload.put("messages", List.of(message));
        payload.put("stream", false);

        Map<String, Object> asrOptions = asrOptions(provider);
        if (!asrOptions.isEmpty()) {
            payload.put("asr_options", asrOptions);
        }
        return payload;
    }

    static Map<String, Object> buildQwenAudioAsrPayload(AiSpeechProvider provider, AsrTranscribeRequest request,
                                                         PreparedAudio audio) {
        Map<String, Object> inputAudio = new LinkedHashMap<>();
        inputAudio.put("data", audioDataUrl(audio.bytes(), audio.format()));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "input_audio");
        content.put("input_audio", inputAudio);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(content));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", audio.format());
        parameters.put("sample_rate", String.valueOf(audio.sampleRate()));
        applyQwenAudioAsrOptions(parameters, provider.getAsrOptionsJson());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", fileAsrModel(provider));
        payload.put("input", Map.of("messages", List.of(message)));
        payload.put("parameters", parameters);
        return payload;
    }

    private static void applyQwenAudioAsrOptions(Map<String, Object> parameters, String json) {
        Dict options = dict(json);
        if (options == null) {
            return;
        }
        copy(options, parameters, "speaker_diarization_enabled", "keep_dialect", "vocabulary_id",
            "vocabulary", "language_hints");
        if (!parameters.containsKey("language_hints") && options.containsKey("languageHints")) {
            parameters.put("language_hints", options.get("languageHints"));
        }
    }

    static PreparedAudio prepareQwenAudio(AsrTranscribeRequest request) {
        String format = StringUtils.blankToDefault(request.format(), "wav").trim().toLowerCase(Locale.ROOT);
        int sampleRate = request.sampleRate() == null || request.sampleRate() <= 0 ? 8000 : request.sampleRate();
        if ("pcm".equals(format) || "l16".equals(format)) {
            return new PreparedAudio(pcmToWav(request.audioBytes(), sampleRate), "wav", sampleRate);
        }
        return new PreparedAudio(request.audioBytes(), format, sampleRate);
    }

    private static byte[] pcmToWav(byte[] pcm, int sampleRate) {
        int dataLength = pcm.length;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(36 + dataLength);
        wav.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        wav.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(sampleRate);
        wav.putInt(sampleRate * 2);
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(dataLength);
        wav.put(pcm);
        return wav.array();
    }

    private static Map<String, Object> asrOptions(AiSpeechProvider provider) {
        Map<String, Object> result = new LinkedHashMap<>();
        Dict options = dict(provider.getAsrOptionsJson());
        if (options != null) {
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                if (!isAsrConfigOnlyOption(entry.getKey()) && entry.getValue() != null) {
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

    static String fileAsrModel(AiSpeechProvider provider) {
        return StringUtils.blankToDefault(provider.getRecordingAsrModel(), DEFAULT_FILE_ASR_MODEL);
    }

    static String realtimeAsrModel(AiSpeechProvider provider) {
        return StringUtils.blankToDefault(provider.getStreamingAsrModel(), DEFAULT_REALTIME_ASR_MODEL);
    }

    private static boolean isAsrConfigOnlyOption(String key) {
        return switch (key) {
            case "model", "fileModel", "realtimeModel", "corpus", "vadThreshold", "prefixPaddingMs" -> true;
            default -> false;
        };
    }

    private static String fileAsrEndpoint(AiSpeechProvider provider, String model) {
        if (isQwenAudioAsrModel(model)) {
            return qwenAudioAsrEndpoint(provider);
        }
        String endpoint = StringUtils.blankToDefault(provider.getRecordingAsrEndpointUrl(), DEFAULT_ASR_ENDPOINT_TEMPLATE).trim();
        String workspaceId = option(provider.getCredentialJson(), "workspaceId");
        if (StringUtils.isBlank(workspaceId)) {
            workspaceId = option(provider.getCredentialJson(), "workspace_id");
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

    static boolean isQwenAudioAsrModel(String model) {
        return StringUtils.isNotBlank(model)
            && model.trim().toLowerCase(Locale.ROOT).startsWith("qwen-audio-")
            && model.trim().toLowerCase(Locale.ROOT).contains("-asr-");
    }

    static String qwenAudioAsrEndpoint(AiSpeechProvider provider) {
        String configured = StringUtils.trimToNull(provider.getRecordingAsrEndpointUrl());
        if ("CUSTOM".equalsIgnoreCase(provider.getRecordingAsrEndpointMode()) && configured != null) {
            return configured;
        }
        String workspaceId = option(provider.getCredentialJson(), "workspaceId");
        if (StringUtils.isBlank(workspaceId)) {
            workspaceId = option(provider.getCredentialJson(), "workspace_id");
        }
        if (StringUtils.isNotBlank(workspaceId)) {
            return QWEN_AUDIO_ASR_ENDPOINT_TEMPLATE.replace("{workspaceId}", workspaceId.trim());
        }
        return DEFAULT_QWEN_AUDIO_ASR_ENDPOINT;
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

    static AsrTranscribeResult parseQwenAudioAsrResponse(String body) {
        JsonNode root;
        try {
            root = JsonUtils.getObjectMapper().readTree(body);
        } catch (Exception exception) {
            throw new ServiceException("解析百炼 Qwen Audio ASR 响应失败：" + exception.getMessage());
        }
        String error = root.path("error").path("message").asText("");
        if (StringUtils.isBlank(error)) {
            error = root.path("message").asText("");
        }
        String code = root.path("code").asText(root.path("error").path("code").asText(""));
        if (StringUtils.isNotBlank(code) || root.has("error")) {
            throw new ServiceException("百炼 Qwen Audio ASR 返回错误，code=" + code + "，message=" + error);
        }
        JsonNode output = root.path("output");
        String text = output.path("text").asText("").trim();
        if (StringUtils.isBlank(text)) {
            text = output.path("sentence").path("text").asText("").trim();
        }
        if (StringUtils.isBlank(text)) {
            throw new ServiceException("百炼 Qwen Audio ASR 未返回识别文本，响应=" + body);
        }
        JsonNode sentence = output.path("sentence");
        Integer index = sentence.has("sentence_id") ? sentence.path("sentence_id").asInt() : 0;
        Integer begin = sentence.has("begin_time") ? sentence.path("begin_time").asInt() : null;
        Integer end = sentence.has("end_time") ? sentence.path("end_time").asInt() : null;
        return new AsrTranscribeResult(text, List.of(new AsrSegment(index, begin, end, text, null, true)));
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

    record PreparedAudio(byte[] bytes, String format, int sampleRate) {
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }
}
