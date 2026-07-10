package org.dromara.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSpeechProvider;
import org.dromara.ai.domain.request.AiRealtimeTtsRequest;
import org.dromara.ai.mapper.AiSpeechProviderMapper;
import org.dromara.ai.provider.StreamingTtsListener;
import org.dromara.ai.provider.StreamingTtsProvider;
import org.dromara.ai.provider.StreamingTtsProviderRegistry;
import org.dromara.ai.provider.StreamingTtsRequest;
import org.dromara.ai.provider.StreamingTtsSession;
import org.dromara.ai.provider.TtsGenerateRequest;
import org.dromara.ai.provider.TtsGenerateResult;
import org.dromara.ai.provider.TtsProviderRegistry;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class AiRealtimeTtsInternalService {

    private static final int DEFAULT_SAMPLE_RATE = 8000;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final long STREAM_TIMEOUT_SECONDS = 15L;

    private final FreeSwitchNodeQueryService nodeQueryService;
    private final AiSpeechProviderMapper providerMapper;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final StreamingTtsProviderRegistry streamingTtsProviderRegistry;
    private final ThreadPoolTaskScheduler aiRealtimeScheduler;
    private final Map<String, CachedAudio> audioCache = new ConcurrentHashMap<>();

    public AiRealtimeTtsInternalService(FreeSwitchNodeQueryService nodeQueryService,
                                        AiSpeechProviderMapper providerMapper,
                                        TtsProviderRegistry ttsProviderRegistry,
                                        StreamingTtsProviderRegistry streamingTtsProviderRegistry,
                                        @Qualifier("aiRealtimeScheduler") ThreadPoolTaskScheduler aiRealtimeScheduler) {
        this.nodeQueryService = nodeQueryService;
        this.providerMapper = providerMapper;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.streamingTtsProviderRegistry = streamingTtsProviderRegistry;
        this.aiRealtimeScheduler = aiRealtimeScheduler;
    }

    public RealtimeTtsAudio generate(String nodeCode, String nodeToken, AiRealtimeTtsRequest request) {
        validateRequest(nodeCode, nodeToken, request);
        Long nodeId = nodeQueryService.resolveEnabledNodeIdByAgentToken(request.getTenantId(), nodeCode, nodeToken);
        if (nodeId == null) {
            throw new ServiceException("AI 实时 TTS 节点鉴权失败");
        }
        return TenantHelper.dynamic(request.getTenantId(), () -> generateInTenant(nodeId, request));
    }

    /**
     * 供 WS 流式端点（{@code AiRealtimeTtsStreamWebSocketHandler}）使用的入口。
     * 跳过节点鉴权（WS 侧另行处理），只做租户级合成。
     */
    public RealtimeTtsAudio generateForStream(AiRealtimeTtsRequest request) {
        if (request == null || StringUtils.isBlank(request.getTenantId()) || StringUtils.isBlank(request.getText())) {
            throw new ServiceException("AI 实时 TTS 请求参数不合法");
        }
        return generateInTenant(null, request);
    }

    /**
     * 真正的流式 TTS 入口：走 {@link StreamingTtsProvider}，逐 chunk 通过 listener 回调裸 PCM。
     *
     * <p>未配置默认流式 TTS 时抛 {@link ServiceException}，由上层（WS handler）决定是否
     * 回退到 {@link #generateForStream(AiRealtimeTtsRequest)} 的 HTTP 一次性合成路径。</p>
     *
     * <p>为避免 provider 永不回调导致 session 泄漏，方法内启动 {@value #STREAM_TIMEOUT_SECONDS}s
     * 兜底定时器；到期后强制 close session 并给 listener 发 {@code onError}。定时器在
     * {@code onCompleted} / {@code onError} 里 cancel。</p>
     *
     * <p>本次不实现文本缓存：不同 chunk 到达是异步的，缓存会破坏 chunk 边界；且实时对话
     * 短句复用率低，缓存收益有限。老 HTTP 路径的缓存不受影响。</p>
     */
    public void generateStream(AiRealtimeTtsRequest request, StreamingTtsListener listener) {
        if (listener == null) {
            throw new ServiceException("AI 实时 TTS 流式合成缺少监听器");
        }
        if (request == null || StringUtils.isBlank(request.getTenantId()) || StringUtils.isBlank(request.getText())) {
            throw new ServiceException("AI 实时 TTS 请求参数不合法");
        }
        if (request.getText().length() > MAX_TEXT_LENGTH) {
            throw new ServiceException("合成文本不能超过" + MAX_TEXT_LENGTH + "个字符");
        }

        AiSpeechProvider provider = defaultStreamingRealtimeProvider();
        StreamingTtsProvider streamingProvider = streamingTtsProviderRegistry.get(provider.getProviderType());

        // 用户已确认走 raw PCM，handler 侧不需要剥 WAV 头。
        String format = "pcm";
        Integer sampleRate = request.getSampleRate();
        String voice = request.getVoice();
        StreamingTtsRequest streamingRequest = new StreamingTtsRequest(voice, format, sampleRate, Map.of());

        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        // session 由外层 open() 返回，onCompleted/onError 里负责 close，
        // generateStream 本身在 commit+finish 后就返回，等待事件异步驱动。
        StreamingTtsSession[] sessionRef = new StreamingTtsSession[1];
        ScheduledFuture<?>[] timerRef = new ScheduledFuture<?>[1];

        StreamingTtsListener wrapped = new StreamingTtsListener() {
            @Override
            public void onStarted() {
                safeInvoke(listener::onStarted, "onStarted");
            }

            @Override
            public void onAudio(byte[] audioBytes) {
                if (finished.get() || timedOut.get()) {
                    return;
                }
                try {
                    listener.onAudio(audioBytes);
                } catch (Exception exception) {
                    log.warn("AI 实时 TTS 流式 onAudio 回调异常", exception);
                }
            }

            @Override
            public void onCompleted() {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                cancelTimer(timerRef);
                safeInvoke(listener::onCompleted, "onCompleted");
                closeQuietly(sessionRef[0]);
            }

            @Override
            public void onError(String message) {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                cancelTimer(timerRef);
                try {
                    listener.onError(message);
                } catch (Exception exception) {
                    log.warn("AI 实时 TTS 流式 onError 回调异常", exception);
                }
                closeQuietly(sessionRef[0]);
            }
        };

        StreamingTtsSession session;
        try {
            session = streamingProvider.open(provider, streamingRequest, wrapped);
        } catch (Exception exception) {
            throw new ServiceException("打开流式 TTS 会话失败：" + rootMessage(exception));
        }
        sessionRef[0] = session;

        timerRef[0] = aiRealtimeScheduler.schedule(() -> {
            if (finished.get()) {
                return;
            }
            timedOut.set(true);
            log.warn("AI 实时 TTS 流式合成超时 {}s，强制关闭 session，providerCode={}",
                STREAM_TIMEOUT_SECONDS, provider.getProviderCode());
            wrapped.onError("流式 TTS 超时");
        }, Instant.now().plus(Duration.ofSeconds(STREAM_TIMEOUT_SECONDS)));

        try {
            session.append(request.getText());
            session.commit();
            session.finish();
        } catch (Exception exception) {
            // 若发送控制事件本身失败，立刻走 onError 收尾。
            wrapped.onError("发送流式 TTS 事件失败：" + rootMessage(exception));
            throw new ServiceException("发送流式 TTS 事件失败：" + rootMessage(exception));
        }
    }

    private AiSpeechProvider defaultStreamingRealtimeProvider() {
        AiSpeechProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiSpeechProvider>()
            .eq(AiSpeechProvider::getEnabled, true)
            .eq(AiSpeechProvider::getStreamingTtsEnabled, true)
            .eq(AiSpeechProvider::getDefaultStreamingTts, true)
            .last("limit 1"));
        if (provider == null) {
            throw new ServiceException("未配置默认流式 TTS 语音服务商");
        }
        // 校验 registry 中确实注册了对应的 StreamingTtsProvider；不匹配会抛异常
        streamingTtsProviderRegistry.get(provider.getProviderType());
        return provider;
    }

    private static void safeInvoke(Runnable action, String tag) {
        try {
            action.run();
        } catch (Exception exception) {
            log.warn("AI 实时 TTS 流式 {} 回调异常", tag, exception);
        }
    }

    private static void cancelTimer(ScheduledFuture<?>[] holder) {
        ScheduledFuture<?> future = holder[0];
        if (future != null) {
            future.cancel(false);
        }
    }

    private static void closeQuietly(StreamingTtsSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception exception) {
            log.debug("关闭流式 TTS session 异常：{}", exception.getMessage());
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return StringUtils.isBlank(message) ? current.getClass().getSimpleName() : message;
    }

    private RealtimeTtsAudio generateInTenant(Long nodeId, AiRealtimeTtsRequest request) {
        AiSpeechProvider provider = defaultRealtimeProvider();
        String outputFormat = normalizeFormat(request.getFormat());
        int sampleRate = request.getSampleRate() == null || request.getSampleRate() <= 0
            ? DEFAULT_SAMPLE_RATE : request.getSampleRate();
        String providerFormat = "pcm".equals(outputFormat) ? "wav" : outputFormat;
        String voice = normalizeVoice(request.getVoice(), provider.getDefaultVoice());
        String cacheKey = cacheKey(request.getTenantId(), provider, voice, outputFormat, sampleRate, request.getText());
        CachedAudio cached = audioCache.get(cacheKey);
        if (cached != null && !cached.expired()) {
            log.info("AI 实时 TTS 命中缓存，nodeId={}，providerCode={}，voice={}，format={}，sampleRate={}，bytes={}",
                nodeId, provider.getProviderCode(), voice, outputFormat, sampleRate, cached.audio().bytes().length);
            return cached.audio();
        }

        // 使用 HashMap 而非 Map.of：WS 流式路径下 nodeId 可能为 null（不需要节点鉴权），
        // Map.of 不允许 null value，会抛 NPE 且 getMessage() 为 null。
        Map<String, Object> providerContext = new HashMap<>();
        providerContext.put("nodeId", nodeId);
        providerContext.put("format", outputFormat);
        TtsGenerateResult result = ttsProviderRegistry.get(provider.getProviderType()).generate(provider,
            new TtsGenerateRequest(request.getText(), voice, providerFormat, sampleRate, "AI_REALTIME_MRCP",
                providerContext));
        if (result == null || result.audioBytes() == null || result.audioBytes().length == 0) {
            throw new ServiceException("AI 实时 TTS 服务未返回音频内容");
        }

        byte[] audio = result.audioBytes();
        String contentType = StringUtils.isBlank(result.contentType()) ? contentType(providerFormat) : result.contentType();
        if ("pcm".equals(outputFormat)) {
            audio = toRawPcm(audio, sampleRate);
            contentType = "audio/L16;rate=" + sampleRate + ";channels=1";
        }
        RealtimeTtsAudio generated = new RealtimeTtsAudio(audio, contentType);
        putCache(cacheKey, generated);
        log.info("AI 实时 TTS 生成成功，nodeId={}，providerCode={}，voice={}，format={}，sampleRate={}，bytes={}",
            nodeId, provider.getProviderCode(), voice, outputFormat, sampleRate, audio.length);
        return generated;
    }

    private AiSpeechProvider defaultRealtimeProvider() {
        AiSpeechProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiSpeechProvider>()
            .eq(AiSpeechProvider::getEnabled, true)
            .eq(AiSpeechProvider::getStreamingTtsEnabled, true)
            .eq(AiSpeechProvider::getDefaultStreamingTts, true)
            .last("limit 1"));
        if (provider == null) {
            provider = providerMapper.selectOne(new LambdaQueryWrapper<AiSpeechProvider>()
                .eq(AiSpeechProvider::getEnabled, true)
                .eq(AiSpeechProvider::getTtsEnabled, true)
                .eq(AiSpeechProvider::getDefaultTts, true)
                .last("limit 1"));
        }
        if (provider == null) {
            throw new ServiceException("未配置默认实时 TTS 或默认 TTS 语音服务商");
        }
        if (!Boolean.TRUE.equals(provider.getTtsEnabled())) {
            throw new ServiceException("默认实时 TTS 服务商必须同时启用 TTS 能力");
        }
        ttsProviderRegistry.get(provider.getProviderType());
        return provider;
    }

    private void validateRequest(String nodeCode, String nodeToken, AiRealtimeTtsRequest request) {
        if (StringUtils.isBlank(nodeCode) || StringUtils.isBlank(nodeToken)) {
            throw new ServiceException("AI 实时 TTS 节点鉴权失败");
        }
        if (request == null || StringUtils.isBlank(request.getTenantId())) {
            throw new ServiceException("租户ID不能为空");
        }
        if (StringUtils.isBlank(request.getText())) {
            throw new ServiceException("合成文本不能为空");
        }
        if (request.getText().length() > MAX_TEXT_LENGTH) {
            throw new ServiceException("合成文本不能超过" + MAX_TEXT_LENGTH + "个字符");
        }
    }

    private String normalizeFormat(String value) {
        if (StringUtils.isBlank(value)) {
            return "pcm";
        }
        String format = value.trim().replace(".", "").toLowerCase();
        if (!"pcm".equals(format) && !"wav".equals(format)) {
            throw new ServiceException("AI 实时 TTS 只支持 pcm 或 wav 格式");
        }
        return format;
    }

    private String normalizeVoice(String requestVoice, String providerDefaultVoice) {
        if (StringUtils.isNotBlank(requestVoice) && !"default".equalsIgnoreCase(requestVoice.trim())) {
            return requestVoice.trim();
        }
        return StringUtils.isBlank(providerDefaultVoice) ? null : providerDefaultVoice.trim();
    }

    private String contentType(String format) {
        return "wav".equals(format) ? "audio/wav" : "application/octet-stream";
    }

    private byte[] toRawPcm(byte[] audio, int expectedSampleRate) {
        if (audio.length < 44 || !startsWith(audio, 0, "RIFF") || !startsWith(audio, 8, "WAVE")) {
            return audio;
        }

        int offset = 12;
        while (offset + 8 <= audio.length) {
            String chunkId = new String(audio, offset, 4, StandardCharsets.US_ASCII);
            long chunkSize = readLittleEndianUnsignedInt(audio, offset + 4);
            int dataStart = offset + 8;
            if ("fmt ".equals(chunkId)) {
                validatePcmFormat(audio, dataStart, chunkSize, expectedSampleRate);
            } else if ("data".equals(chunkId)) {
                return extractDataChunk(audio, dataStart, chunkSize);
            }
            long nextOffset = dataStart + chunkSize + (chunkSize % 2);
            if (nextOffset <= offset || nextOffset > Integer.MAX_VALUE) {
                throw new ServiceException("TTS WAV 音频 chunk 结构异常");
            }
            offset = (int) nextOffset;
        }
        throw new ServiceException("TTS WAV 音频未找到 data 块");
    }

    private byte[] extractDataChunk(byte[] audio, int dataStart, long chunkSize) {
        if (dataStart >= audio.length) {
            throw new ServiceException("TTS WAV 音频 data 块为空");
        }
        int remaining = audio.length - dataStart;
        int copyLength;
        if (chunkSize <= 0 || chunkSize > remaining) {
            // Some cloud vendors return streaming WAV with a placeholder data size.
            log.warn("TTS WAV data 块长度与实际音频不一致，按实际剩余字节处理，declaredSize={}，remaining={}",
                chunkSize, remaining);
            copyLength = remaining;
        } else {
            copyLength = (int) chunkSize;
        }
        byte[] pcm = new byte[copyLength];
        System.arraycopy(audio, dataStart, pcm, 0, copyLength);
        return pcm;
    }

    private void validatePcmFormat(byte[] audio, int offset, long chunkSize, int expectedSampleRate) {
        if (chunkSize < 16 || offset + 16 > audio.length) {
            throw new ServiceException("TTS WAV 音频 fmt 块异常");
        }
        int audioFormat = readLittleEndianShort(audio, offset);
        int channels = readLittleEndianShort(audio, offset + 2);
        int sampleRate = (int) readLittleEndianUnsignedInt(audio, offset + 4);
        int bitsPerSample = readLittleEndianShort(audio, offset + 14);
        if (audioFormat != 1 || channels != 1 || bitsPerSample != 16) {
            throw new ServiceException("AI 实时 MRCP TTS 需要 16bit 单声道 PCM WAV，请调整语音服务商输出参数");
        }
        if (sampleRate != expectedSampleRate) {
            throw new ServiceException("AI 实时 MRCP TTS 采样率不匹配，期望 " + expectedSampleRate
                + "Hz，实际 " + sampleRate + "Hz，请调整语音服务商输出参数");
        }
    }

    private boolean startsWith(byte[] audio, int offset, String value) {
        if (offset + value.length() > audio.length) {
            return false;
        }
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < expected.length; i++) {
            if (audio[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private int readLittleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private long readLittleEndianUnsignedInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xff)
            | (((long) bytes[offset + 1] & 0xff) << 8)
            | (((long) bytes[offset + 2] & 0xff) << 16)
            | (((long) bytes[offset + 3] & 0xff) << 24);
    }

    private String cacheKey(String tenantId, AiSpeechProvider provider, String voice, String format, int sampleRate, String text) {
        return tenantId + ':' + provider.getId() + ':' + provider.getVersion() + ':' + voice + ':' + format + ':'
            + sampleRate + ':' + sha256(text);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void putCache(String cacheKey, RealtimeTtsAudio audio) {
        if (audioCache.size() >= MAX_CACHE_ENTRIES) {
            cleanupCache(true);
        }
        audioCache.put(cacheKey, new CachedAudio(audio, System.currentTimeMillis() + CACHE_TTL_MILLIS));
    }

    private void cleanupCache(boolean forceEvictOne) {
        long now = System.currentTimeMillis();
        String firstKey = null;
        for (Map.Entry<String, CachedAudio> entry : audioCache.entrySet()) {
            if (entry.getValue().expiresAt() <= now) {
                audioCache.remove(entry.getKey());
            } else if (firstKey == null) {
                firstKey = entry.getKey();
            }
        }
        if (forceEvictOne && audioCache.size() >= MAX_CACHE_ENTRIES && firstKey != null) {
            audioCache.remove(firstKey);
        }
    }

    public record RealtimeTtsAudio(byte[] bytes, String contentType) {
    }

    private record CachedAudio(RealtimeTtsAudio audio, long expiresAt) {
        boolean expired() {
            return expiresAt <= System.currentTimeMillis();
        }
    }
}
