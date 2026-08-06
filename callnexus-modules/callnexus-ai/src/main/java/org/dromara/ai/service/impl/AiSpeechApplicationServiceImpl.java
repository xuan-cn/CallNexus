package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.event.AiTranscriptLifecycleEvent;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.AiGeneratedMediaQueryService;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.ai.service.AiSpeechProviderSelector;
import org.dromara.ai.support.ByteArrayAudioMultipartFile;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.OssService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.media.domain.MediaAssetCategory;
import org.dromara.resource.media.service.MediaAssetApplicationService;
import org.dromara.resource.media.service.MediaPublicationService;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroup;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiSpeechApplicationServiceImpl implements AiSpeechApplicationService {
    public static final String BUSINESS_AGENT_NUMBER_PROMPT = "AGENT_NUMBER_PROMPT";
    private static final String BUSINESS_CALL_TRANSCRIPT = "CALL_TRANSCRIPT";
    private static final String TASK_TTS = "TTS";
    private static final String TASK_ASR = "ASR";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String SPEAKER_UNKNOWN = "UNKNOWN";
    private static final String SOURCE_RECORDING_ASR = "RECORDING_ASR";
    private static final String DEFAULT_AGENT_TEMPLATE = "工号{extension}为您服务";
    private static final Duration RECORDING_DOWNLOAD_TTL = Duration.ofHours(2);

    private final AiSpeechProviderMapper providerMapper;
    private final AiSpeechTemplateMapper templateMapper;
    private final AiSpeechTaskMapper taskMapper;
    private final AiGeneratedMediaMapper generatedMediaMapper;
    private final AiCallTranscriptMapper transcriptMapper;
    private final AiCallTranscriptSegmentMapper transcriptSegmentMapper;
    private final AiCallRecordingSourceMapper recordingSourceMapper;
    private final AiCallEventMapper callEventMapper;
    private final FreeSwitchNodeGroupMapper nodeGroupMapper;
    private final MediaAssetApplicationService mediaAssetService;
    private final MediaPublicationService mediaPublicationService;
    private final AiGeneratedMediaQueryService generatedMediaQueryService;
    private final TtsProviderRegistry providerRegistry;
    private final AsrProviderRegistry asrProviderRegistry;
    private final StreamingAsrProviderRegistry streamingAsrProviderRegistry;
    private final StreamingTtsProviderRegistry streamingTtsProviderRegistry;
    private final AiSpeechProviderSelector providerSelector;
    private final OssService ossService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public List<AiSpeechProviderResponse> providers() {
        return providerMapper.selectList(new LambdaQueryWrapper<AiSpeechProvider>().orderByAsc(AiSpeechProvider::getProviderCode))
            .stream().map(this::providerResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProvider(AiSpeechProviderRequest request) {
        ensureProviderCodeUnique(request.getProviderCode(), null);
        AiSpeechProvider provider = new AiSpeechProvider();
        fillProvider(provider, request, true);
        validateProvider(provider);
        clearOtherDefaults(provider, null);
        providerMapper.insert(provider);
        return provider.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProvider(Long id, AiSpeechProviderRequest request) {
        ensureProviderCodeUnique(request.getProviderCode(), id);
        AiSpeechProvider provider = requireProvider(id);
        validateDefaultMutation(provider, request);
        fillProvider(provider, request, false);
        validateProvider(provider);
        clearOtherDefaults(provider, id);
        provider.setVersion(request.getVersion());
        if (providerMapper.updateById(provider) != 1) {
            throw new ServiceException("语音服务商已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    public void deleteProvider(Long id) {
        AiSpeechProvider provider = requireProvider(id);
        if (isAnyDefault(provider)) {
            throw new ServiceException("默认语音服务商不能删除，请先指定其他默认服务商");
        }
        if (providerMapper.deleteById(id) != 1) {
            throw new ServiceException("语音服务商不存在");
        }
    }

    @Override
    public TtsTestResponse testProvider(Long id, TtsTestRequest request) {
        AiSpeechProvider provider = requireEnabledTtsProvider(id);
        TtsGenerateResult result = generateAudio(provider, request.getText(), chooseVoice(provider, request.getVoice()), "TTS_TEST", Map.of());
        if (result == null || result.audioBytes() == null || result.audioBytes().length == 0) {
            throw new ServiceException("TTS 服务未返回音频内容");
        }
        String contentType = StringUtils.isBlank(result.contentType()) ? "audio/wav" : result.contentType();
        String playbackUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(result.audioBytes());
        return new TtsTestResponse(null, playbackUrl);
    }

    @Override
    public AsrTestResponse testAsrProvider(Long id, MultipartFile file, String format, Integer sampleRate) {
        AiSpeechProvider provider = requireEnabledRecordingAsrProvider(id);
        validateAsrTestFile(file);
        String resolvedFormat = resolveAsrTestFormat(file, format, provider.getAsrFormat());
        try {
            AsrTranscribeResult result = asrProviderRegistry.get(provider.getProviderType()).transcribe(provider,
                new AsrTranscribeRequest(file.getBytes(), resolvedFormat, sampleRate, "ASR_TEST",
                    Map.of("fileName", StringUtils.blankToDefault(file.getOriginalFilename(), "unknown"))));
            return new AsrTestResponse(result.fullText(), result.segments());
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("读取 ASR 测试文件失败：" + exception.getMessage());
        }
    }

    @Override
    public List<AiSpeechTemplateResponse> templates() {
        return templateMapper.selectList(new LambdaQueryWrapper<AiSpeechTemplate>().orderByAsc(AiSpeechTemplate::getTemplateCode))
            .stream().map(this::templateResponse).toList();
    }

    @Override
    public Long createTemplate(AiSpeechTemplateRequest request) {
        ensureTemplateCodeUnique(request.getTemplateCode(), null);
        AiSpeechTemplate template = new AiSpeechTemplate();
        fillTemplate(template, request, true);
        templateMapper.insert(template);
        return template.getId();
    }

    @Override
    public void updateTemplate(Long id, AiSpeechTemplateRequest request) {
        ensureTemplateCodeUnique(request.getTemplateCode(), id);
        AiSpeechTemplate template = requireTemplate(id);
        fillTemplate(template, request, false);
        template.setVersion(request.getVersion());
        if (templateMapper.updateById(template) != 1) {
            throw new ServiceException("语音模板已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    public void deleteTemplate(Long id) {
        if (templateMapper.deleteById(id) != 1) {
            throw new ServiceException("语音模板不存在");
        }
    }

    @Override
    public TableDataInfo<AiSpeechTaskResponse> tasks(AiSpeechTaskPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<AiSpeechTask> wrapper = new LambdaQueryWrapper<AiSpeechTask>()
            .eq(StringUtils.isNotBlank(query.getTaskType()), AiSpeechTask::getTaskType, query.getTaskType())
            .eq(StringUtils.isNotBlank(query.getBusinessType()), AiSpeechTask::getBusinessType, query.getBusinessType())
            .eq(StringUtils.isNotBlank(query.getStatus()), AiSpeechTask::getStatus, query.getStatus())
            .orderByDesc(AiSpeechTask::getCreateTime);
        Page<AiSpeechTask> page = taskMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::taskResponse).toList(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiGeneratedMediaResponse generateAgentNumberPrompt(Long agentId, String extension, List<Long> nodeGroupIds, Long templateId) {
        if (agentId == null || StringUtils.isBlank(extension)) {
            throw new ServiceException("坐席或分机为空，无法生成工号提示音");
        }
        AiSpeechProvider provider = providerSelector.requireDefaultTts();
        AiSpeechTemplate template = agentPromptTemplate(templateId);
        String text = agentPromptText(extension, template);
        String voice = chooseVoice(provider, template == null ? null : template.getDefaultVoice());
        AiSpeechTask task = createTask(BUSINESS_AGENT_NUMBER_PROMPT, agentId, provider, voice, text);
        AiGeneratedMedia current = currentBinding(BUSINESS_AGENT_NUMBER_PROMPT, agentId);
        Long currentMediaId = current == null ? null : current.getMediaId();
        AiGeneratedMedia binding = upsertBinding(BUSINESS_AGENT_NUMBER_PROMPT, agentId, currentMediaId, task.getId(), "PROCESSING", null, textHash(text));
        try {
            TtsGenerateResult result = generateAudio(provider, text, voice, BUSINESS_AGENT_NUMBER_PROMPT,
                Map.of("agentId", agentId, "extension", extension));
            Long mediaId = storeGeneratedMedia("坐席工号提示音" + extension, MediaAssetCategory.AGENT_PROMPT, text, provider, result, task.getId());
            publish(mediaId, nodeGroupIds);
            task.setOutputMediaId(mediaId);
            task.setStatus(STATUS_SUCCESS);
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            binding = upsertBinding(BUSINESS_AGENT_NUMBER_PROMPT, agentId, mediaId, task.getId(), STATUS_SUCCESS, null, textHash(text));
            log.info("坐席工号提示音生成完成，agentId={}，extension={}，mediaId={}，taskId={}", agentId, extension, mediaId, task.getId());
            return bindingResponse(binding, null);
        } catch (Exception exception) {
            task.setStatus(STATUS_FAILED);
            task.setFailureReason(exception.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            binding = upsertBinding(BUSINESS_AGENT_NUMBER_PROMPT, agentId, currentMediaId, task.getId(), STATUS_FAILED, exception.getMessage(), textHash(text));
            log.warn("坐席工号提示音生成失败，agentId={}，extension={}，taskId={}，error={}", agentId, extension, task.getId(), exception.getMessage());
            return bindingResponse(binding, null);
        }
    }

    @Override
    public AiGeneratedMediaResponse agentNumberPrompt(Long agentId, Long nodeId) {
        AiGeneratedMedia binding = generatedMediaMapper.selectOne(new LambdaQueryWrapper<AiGeneratedMedia>()
            .eq(AiGeneratedMedia::getBusinessType, BUSINESS_AGENT_NUMBER_PROMPT)
            .eq(AiGeneratedMedia::getBusinessId, agentId)
            .orderByDesc(AiGeneratedMedia::getGeneratedAt)
            .last("limit 1"));
        if (binding == null) {
            return null;
        }
        return bindingResponse(binding, generatedMediaQueryService.findSyncedPath(BUSINESS_AGENT_NUMBER_PROMPT, agentId, nodeId));
    }

    @Override
    public AiCallTranscriptResponse callTranscript(Long callSessionId) {
        AiCallTranscript transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getCallSessionId, callSessionId)
            .last("limit 1"));
        return transcript == null ? null : transcriptResponse(transcript);
    }

    @Override
    public AiCallTranscriptResponse callTranscriptByBusinessCallId(String businessCallId) {
        if (StringUtils.isBlank(businessCallId)) {
            throw new ServiceException("业务通话ID不能为空");
        }
        AiCallTranscript transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getBusinessCallId, businessCallId)
            .orderByDesc(AiCallTranscript::getCreateTime)
            .last("limit 1"));
        return transcript == null ? null : transcriptResponse(transcript);
    }

    @Override
    @Transactional(noRollbackFor = ServiceException.class)
    public AiCallTranscriptResponse transcribeCallRecording(Long callSessionId) {
        AiCallRecordingSource source = recordingSourceMapper.selectById(callSessionId);
        if (source == null) {
            throw new ServiceException("通话记录不存在");
        }
        if (source.getRecordingOssId() == null) {
            throw new ServiceException("通话录音不存在，无法转写");
        }
        AiSpeechProvider provider = providerSelector.requireDefaultRecordingAsr();
        AiSpeechTask task = createAsrTask(source, provider);
        AiCallTranscript transcript = prepareTranscript(source, provider);
        try {
            AudioClip audioClip = asrAudioClip(source, downloadRecording(source.getRecordingOssId()));
            AsrTranscribeResult result = asrProviderRegistry.get(provider.getProviderType()).transcribe(provider,
                new AsrTranscribeRequest(audioClip.audioBytes(), recordingFormat(source), provider.getAsrSampleRate(),
                    BUSINESS_CALL_TRANSCRIPT, Map.of(
                    "callSessionId", callSessionId,
                    "businessCallId", source.getBusinessCallId(),
                    "trimStartMs", audioClip.offsetMs()
                )));
            saveTranscriptSuccess(transcript, result, audioClip.offsetMs(), SPEAKER_UNKNOWN, SOURCE_RECORDING_ASR, null, null);
            task.setTextContent(result.fullText());
            task.setStatus(STATUS_SUCCESS);
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            log.info("通话录音转写完成，callSessionId={}，businessCallId={}，providerCode={}，segments={}",
                source.getId(), source.getBusinessCallId(), provider.getProviderCode(), result.segments().size());
            publishTranscriptEvent("transcript.ready", source.getNodeId(), transcript, null);
            return transcriptResponse(transcriptMapper.selectById(transcript.getId()));
        } catch (Exception exception) {
            String message = StringUtils.blankToDefault(exception.getMessage(), "未知错误");
            transcript.setStatus(STATUS_FAILED);
            transcript.setFailureReason(message);
            transcript.setFinishedAt(LocalDateTime.now());
            transcriptMapper.updateById(transcript);
            task.setStatus(STATUS_FAILED);
            task.setFailureReason(message);
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            log.warn("通话录音转写失败，callSessionId={}，businessCallId={}，providerCode={}，error={}",
                source.getId(), source.getBusinessCallId(), provider.getProviderCode(), message);
            publishTranscriptEvent("transcript.failed", source.getNodeId(), transcript, message);
            throw exception instanceof ServiceException ? (ServiceException) exception : new ServiceException("通话录音转写失败：" + message);
        }
    }

    private void publishTranscriptEvent(String eventType, Long nodeId, AiCallTranscript transcript, String failureReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transcript_id", transcript.getId());
        payload.put("transcript_status", transcript.getStatus());
        payload.put("call_session_id", transcript.getCallSessionId());
        payload.put("input_media_id", transcript.getInputMediaId());
        if (StringUtils.isNotBlank(failureReason)) {
            payload.put("failure_reason", failureReason);
        }
        eventPublisher.publishEvent(new AiTranscriptLifecycleEvent(TenantHelper.getTenantId(), eventType,
            transcript.getBusinessCallId(), nodeId, LocalDateTime.now(), payload));
    }

    private TtsGenerateResult generateAudio(AiSpeechProvider provider, String text, String voice, String businessType, Map<String, Object> metadata) {
        taskSanity(text);
        TtsGenerateRequest request = new TtsGenerateRequest(text, voice,
            StringUtils.isBlank(provider.getDefaultFormat()) ? "wav" : provider.getDefaultFormat(),
            provider.getDefaultSampleRate() == null ? 8000 : provider.getDefaultSampleRate(),
            businessType, metadata);
        return providerRegistry.get(provider.getProviderType()).generate(provider, request);
    }

    private AiSpeechTask createAsrTask(AiCallRecordingSource source, AiSpeechProvider provider) {
        AiSpeechTask task = new AiSpeechTask();
        task.setTaskType(TASK_ASR);
        task.setBusinessType(BUSINESS_CALL_TRANSCRIPT);
        task.setBusinessId(source.getId());
        task.setProviderId(provider.getId());
        task.setProviderType(provider.getProviderType());
        task.setInputMediaId(source.getRecordingMediaId());
        task.setStatus(STATUS_PROCESSING);
        task.setRetryCount(0);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    private AiCallTranscript prepareTranscript(AiCallRecordingSource source, AiSpeechProvider provider) {
        AiCallTranscript transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getCallSessionId, source.getId())
            .last("limit 1"));
        if (transcript == null) {
            transcript = new AiCallTranscript();
            transcript.setCallSessionId(source.getId());
            transcript.setBusinessCallId(source.getBusinessCallId());
        }
        transcript.setProviderId(provider.getId());
        transcript.setProviderType(provider.getProviderType());
        transcript.setInputMediaId(source.getRecordingMediaId());
        transcript.setRecordingOssId(source.getRecordingOssId());
        transcript.setStatus(STATUS_PROCESSING);
        transcript.setFullText(null);
        transcript.setFailureReason(null);
        transcript.setStartedAt(LocalDateTime.now());
        transcript.setFinishedAt(null);
        if (transcript.getId() == null) {
            transcriptMapper.insert(transcript);
        } else {
            transcriptMapper.updateById(transcript);
        }
        transcriptSegmentMapper.delete(new LambdaQueryWrapper<AiCallTranscriptSegment>()
            .eq(AiCallTranscriptSegment::getTranscriptId, transcript.getId()));
        return transcript;
    }

    private void saveTranscriptSuccess(AiCallTranscript transcript, AsrTranscribeResult result, int offsetMs,
                                       String speaker, String sourceType, String legUuid, Long agentId) {
        transcript.setStatus(STATUS_SUCCESS);
        transcript.setFullText(result.fullText());
        transcript.setFailureReason(null);
        transcript.setFinishedAt(LocalDateTime.now());
        transcriptMapper.updateById(transcript);
        appendTranscriptSegments(transcript, result, offsetMs, speaker, sourceType, legUuid, agentId);
    }

    private void appendTranscriptSegments(AiCallTranscript transcript, AsrTranscribeResult result, int offsetMs,
                                          String speaker, String sourceType, String legUuid, Long agentId) {
        if (result.segments() != null) {
            for (AsrSegment segment : result.segments()) {
                if (StringUtils.isBlank(segment.text())) {
                    continue;
                }
                AiCallTranscriptSegment entity = new AiCallTranscriptSegment();
                entity.setTranscriptId(transcript.getId());
                entity.setCallSessionId(transcript.getCallSessionId());
                entity.setBusinessCallId(transcript.getBusinessCallId());
                entity.setSpeaker(StringUtils.blankToDefault(speaker, SPEAKER_UNKNOWN));
                entity.setSourceType(StringUtils.blankToDefault(sourceType, SOURCE_RECORDING_ASR));
                entity.setLegUuid(legUuid);
                entity.setAgentId(agentId);
                entity.setSentenceIndex(segment.sentenceIndex());
                entity.setStartMs(offsetTime(segment.startMs(), offsetMs));
                entity.setEndMs(offsetTime(segment.endMs(), offsetMs));
                entity.setMessageTime(resolveMessageTime(transcript.getStartedAt(), entity.getStartMs()));
                entity.setTextContent(segment.text());
                entity.setFinalResult(segment.finalResult());
                entity.setConfidence(segment.confidence());
                transcriptSegmentMapper.insert(entity);
            }
        }
    }

    private LocalDateTime resolveMessageTime(LocalDateTime transcriptStartedAt, Integer startMs) {
        if (transcriptStartedAt == null || startMs == null) {
            return null;
        }
        return transcriptStartedAt.plus(startMs, ChronoUnit.MILLIS);
    }

    private Integer offsetTime(Integer value, int offsetMs) {
        return value == null ? null : value + offsetMs;
    }

    private byte[] downloadRecording(Long recordingOssId) {
        String url = ossService.selectUrlById(recordingOssId, RECORDING_DOWNLOAD_TTL);
        if (StringUtils.isBlank(url)) {
            throw new ServiceException("录音文件访问地址为空");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(RECORDING_DOWNLOAD_TTL)
                .GET()
                .build();
            HttpResponse<byte[]> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException("下载录音文件失败，HTTP状态码=" + response.statusCode());
            }
            return response.body();
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("下载录音文件失败：" + exception.getMessage());
        }
    }

    private AudioClip asrAudioClip(AiCallRecordingSource source, byte[] audioBytes) {
        int trimStartMs = resolveTrimStartMs(source);
        if (trimStartMs <= 0) {
            return new AudioClip(audioBytes, 0);
        }
        try {
            byte[] trimmed = trimAudio(audioBytes, recordingFormat(source), trimStartMs);
            log.info("通话录音 ASR 已跳过等待音片段，callSessionId={}，businessCallId={}，trimStartMs={}，originalBytes={}，trimmedBytes={}",
                source.getId(), source.getBusinessCallId(), trimStartMs, audioBytes.length, trimmed.length);
            return new AudioClip(trimmed, trimStartMs);
        } catch (Exception exception) {
            log.warn("通话录音 ASR 裁剪等待音失败，降级整段识别，callSessionId={}，businessCallId={}，trimStartMs={}，error={}",
                source.getId(), source.getBusinessCallId(), trimStartMs, exception.getMessage());
            return new AudioClip(audioBytes, 0);
        }
    }

    private int resolveTrimStartMs(AiCallRecordingSource source) {
        if (source.getStartedAt() == null) {
            return 0;
        }
        LocalDateTime speechStartedAt = resolveAgentAnswerTime(source.getId());
        if (speechStartedAt == null) {
            speechStartedAt = source.getAnsweredAt();
        }
        if (speechStartedAt == null || !speechStartedAt.isAfter(source.getStartedAt())) {
            return 0;
        }
        long millis = ChronoUnit.MILLIS.between(source.getStartedAt(), speechStartedAt);
        if (millis < 1000) {
            return 0;
        }
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }

    private LocalDateTime resolveAgentAnswerTime(Long callSessionId) {
        AiCallEvent event = callEventMapper.selectOne(new LambdaQueryWrapper<AiCallEvent>()
            .eq(AiCallEvent::getSessionId, callSessionId)
            .eq(AiCallEvent::getEventType, "AGENT_ANSWER")
            .orderByAsc(AiCallEvent::getOccurredAt)
            .last("limit 1"));
        return event == null ? null : event.getOccurredAt();
    }

    private byte[] trimAudio(byte[] audioBytes, String format, int trimStartMs) throws Exception {
        String suffix = "." + (StringUtils.isBlank(format) ? "wav" : format.toLowerCase());
        Path input = Files.createTempFile("callnexus-asr-source-", suffix);
        Path output = Files.createTempFile("callnexus-asr-trimmed-", ".wav");
        try {
            Files.write(input, audioBytes);
            Process process = new ProcessBuilder(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-ss", String.format(java.util.Locale.ROOT, "%.3f", trimStartMs / 1000.0),
                "-i", input.toString(),
                "-ac", "1",
                "-ar", "8000",
                "-acodec", "pcm_s16le",
                output.toString()
            ).redirectErrorStream(true).start();
            byte[] processOutput = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("ffmpeg 退出码=" + exitCode + "，输出=" + new String(processOutput, StandardCharsets.UTF_8));
            }
            byte[] trimmed = Files.readAllBytes(output);
            if (trimmed.length == 0) {
                throw new IllegalStateException("ffmpeg 裁剪后音频为空");
            }
            return trimmed;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    private String recordingFormat(AiCallRecordingSource source) {
        String fileName = source.getRecordingFileName();
        if (StringUtils.isBlank(fileName) || !fileName.contains(".")) {
            return "wav";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1);
    }

    private record AudioClip(byte[] audioBytes, int offsetMs) {
    }

    private Long storeGeneratedMedia(String assetName, MediaAssetCategory category, String text, AiSpeechProvider provider,
                                     TtsGenerateResult result, Long taskId) {
        if (result == null || result.audioBytes() == null || result.audioBytes().length == 0) {
            throw new ServiceException("TTS 服务未返回音频内容");
        }
        String suffix = StringUtils.isBlank(result.fileSuffix()) ? ".wav" : result.fileSuffix();
        String contentType = StringUtils.isBlank(result.contentType()) ? "audio/wav" : result.contentType();
        ByteArrayAudioMultipartFile file = new ByteArrayAudioMultipartFile("file", "tts-" + System.currentTimeMillis() + suffix, contentType, result.audioBytes());
        Long existingMediaId = existingGeneratedMediaId(taskId);
        String remark = "AI语音生成任务：" + taskId;
        if (existingMediaId != null) {
            return mediaAssetService.storeGeneratedVersion(existingMediaId, assetName, "zh-CN", remark,
                result.durationMs(), text, provider.getProviderCode(), provider.getDefaultVoice(), file);
        }
        return mediaAssetService.storeGenerated(assetName, category, "zh-CN", "AI语音生成任务：" + taskId,
            result.durationMs(), text, provider.getProviderCode(), provider.getDefaultVoice(), file);
    }

    private void publish(Long mediaId, List<Long> nodeGroupIds) {
        List<Long> groups = nodeGroupIds == null || nodeGroupIds.isEmpty()
            ? nodeGroupMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroup>().eq(FreeSwitchNodeGroup::getEnabled, true))
                .stream().map(FreeSwitchNodeGroup::getId).toList()
            : nodeGroupIds;
        if (groups.isEmpty()) {
            throw new ServiceException("没有可用 FreeSWITCH 节点组，无法发布生成音频");
        }
        mediaPublicationService.publish(mediaId, groups);
    }

    private AiSpeechTemplate agentPromptTemplate(Long templateId) {
        if (templateId != null) {
            AiSpeechTemplate template = templateMapper.selectById(templateId);
            if (template == null || !BUSINESS_AGENT_NUMBER_PROMPT.equals(template.getBusinessType()) || !Boolean.TRUE.equals(template.getEnabled())) {
                throw new ServiceException("坐席工号提示音模板不存在或未启用");
            }
            return template;
        }
        return templateMapper.selectOne(new LambdaQueryWrapper<AiSpeechTemplate>()
            .eq(AiSpeechTemplate::getBusinessType, BUSINESS_AGENT_NUMBER_PROMPT)
            .eq(AiSpeechTemplate::getEnabled, true)
            .orderByAsc(AiSpeechTemplate::getCreateTime)
            .last("limit 1"));
    }

    private String agentPromptText(String extension, AiSpeechTemplate template) {
        String pattern = template == null || StringUtils.isBlank(template.getTemplateText()) ? DEFAULT_AGENT_TEMPLATE : template.getTemplateText();
        return pattern.replace("{extension}", spokenExtension(extension));
    }

    private String spokenExtension(String extension) {
        if (StringUtils.isBlank(extension)) {
            return "";
        }
        Map<Character, String> digits = Map.of(
            '0', "零",
            '1', "一",
            '2', "二",
            '3', "三",
            '4', "四",
            '5', "五",
            '6', "六",
            '7', "七",
            '8', "八",
            '9', "九"
        );
        StringBuilder builder = new StringBuilder();
        for (char ch : extension.toCharArray()) {
            builder.append(digits.getOrDefault(ch, String.valueOf(ch)));
        }
        return builder.toString();
    }

    private AiSpeechTask createTask(String businessType, Long businessId, AiSpeechProvider provider, String voice, String text) {
        AiSpeechTask task = new AiSpeechTask();
        task.setTaskType(TASK_TTS);
        task.setBusinessType(businessType);
        task.setBusinessId(businessId);
        task.setProviderId(provider.getId());
        task.setProviderType(provider.getProviderType());
        task.setVoiceName(voice);
        task.setTextContent(text);
        task.setStatus("PROCESSING");
        task.setRetryCount(0);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    private AiGeneratedMedia upsertBinding(String businessType, Long businessId, Long mediaId, Long taskId, String status, String failureReason, String textHash) {
        AiGeneratedMedia binding = currentBinding(businessType, businessId);
        if (binding == null) {
            binding = new AiGeneratedMedia();
            binding.setBusinessType(businessType);
            binding.setBusinessId(businessId);
        }
        binding.setMediaId(mediaId);
        binding.setTaskId(taskId);
        binding.setTextHash(textHash);
        binding.setGenerationStatus(status);
        binding.setFailureReason(failureReason);
        binding.setGeneratedAt(LocalDateTime.now());
        if (binding.getId() == null) {
            generatedMediaMapper.insert(binding);
        } else {
            generatedMediaMapper.updateById(binding);
        }
        return binding;
    }

    private AiGeneratedMedia currentBinding(String businessType, Long businessId) {
        return generatedMediaMapper.selectOne(new LambdaQueryWrapper<AiGeneratedMedia>()
            .eq(AiGeneratedMedia::getBusinessType, businessType)
            .eq(AiGeneratedMedia::getBusinessId, businessId)
            .last("limit 1"));
    }

    private Long existingGeneratedMediaId(Long taskId) {
        if (taskId == null) {
            return null;
        }
        AiSpeechTask task = taskMapper.selectById(taskId);
        if (task == null || !BUSINESS_AGENT_NUMBER_PROMPT.equals(task.getBusinessType())) {
            return null;
        }
        AiGeneratedMedia binding = currentBinding(task.getBusinessType(), task.getBusinessId());
        return binding == null ? null : binding.getMediaId();
    }

    private void fillProvider(AiSpeechProvider provider, AiSpeechProviderRequest request, boolean create) {
        provider.setProviderCode(request.getProviderCode());
        provider.setProviderName(request.getProviderName());
        provider.setProviderType(request.getProviderType().trim().toUpperCase());
        provider.setTtsEnabled(request.getTtsEnabled() == null ? (create || Boolean.TRUE.equals(provider.getTtsEnabled())) : request.getTtsEnabled());
        provider.setStreamingTtsEnabled(request.getStreamingTtsEnabled() == null
            ? (!create && Boolean.TRUE.equals(provider.getStreamingTtsEnabled())) : request.getStreamingTtsEnabled());
        provider.setRecordingAsrEnabled(request.getRecordingAsrEnabled() == null
            ? (!create && Boolean.TRUE.equals(provider.getRecordingAsrEnabled())) : request.getRecordingAsrEnabled());
        provider.setStreamingAsrEnabled(request.getStreamingAsrEnabled() == null
            ? (!create && Boolean.TRUE.equals(provider.getStreamingAsrEnabled())) : request.getStreamingAsrEnabled());
        provider.setDefaultTts(request.getDefaultTts() == null
            ? (!create && Boolean.TRUE.equals(provider.getDefaultTts())) : request.getDefaultTts());
        provider.setDefaultStreamingTts(request.getDefaultStreamingTts() == null
            ? (!create && Boolean.TRUE.equals(provider.getDefaultStreamingTts())) : request.getDefaultStreamingTts());
        provider.setDefaultRecordingAsr(request.getDefaultRecordingAsr() == null
            ? (!create && Boolean.TRUE.equals(provider.getDefaultRecordingAsr())) : request.getDefaultRecordingAsr());
        provider.setDefaultStreamingAsr(request.getDefaultStreamingAsr() == null
            ? (!create && Boolean.TRUE.equals(provider.getDefaultStreamingAsr())) : request.getDefaultStreamingAsr());
        provider.setEndpointUrl(request.getEndpointUrl());
        provider.setHttpMethod(StringUtils.isBlank(request.getHttpMethod()) ? "POST" : request.getHttpMethod().trim().toUpperCase());
        provider.setAuthType(StringUtils.isBlank(request.getAuthType()) ? "NONE" : request.getAuthType().trim().toUpperCase());
        provider.setAuthHeaderName(request.getAuthHeaderName());
        if (StringUtils.isNotBlank(request.getAuthToken())) {
            provider.setAuthToken(request.getAuthToken());
        } else if (create) {
            provider.setAuthToken(null);
        }
        provider.setDefaultVoice(StringUtils.isBlank(request.getDefaultVoice()) ? "default" : request.getDefaultVoice());
        provider.setDefaultFormat(StringUtils.isBlank(request.getDefaultFormat()) ? "wav" : request.getDefaultFormat());
        provider.setDefaultSampleRate(request.getDefaultSampleRate() == null ? 8000 : request.getDefaultSampleRate());
        provider.setTimeoutSeconds(request.getTimeoutSeconds() == null ? 30 : request.getTimeoutSeconds());
        if (create || request.getStreamingTtsEndpointUrl() != null) provider.setStreamingTtsEndpointUrl(request.getStreamingTtsEndpointUrl());
        if (create || request.getStreamingTtsOptionsJson() != null) provider.setStreamingTtsOptionsJson(request.getStreamingTtsOptionsJson());
        if (create || request.getRecordingAsrEndpointUrl() != null) provider.setRecordingAsrEndpointUrl(request.getRecordingAsrEndpointUrl());
        if (create || request.getStreamingAsrEndpointUrl() != null) provider.setStreamingAsrEndpointUrl(request.getStreamingAsrEndpointUrl());
        provider.setAsrLanguage(request.getAsrLanguage() == null
            ? (create ? "zh-CN" : provider.getAsrLanguage()) : StringUtils.blankToDefault(request.getAsrLanguage(), "zh-CN"));
        provider.setAsrFormat(request.getAsrFormat() == null
            ? (create ? "wav" : provider.getAsrFormat()) : StringUtils.blankToDefault(request.getAsrFormat(), "wav").toLowerCase());
        provider.setAsrSampleRate(request.getAsrSampleRate() == null ? (create ? 8000 : provider.getAsrSampleRate()) : request.getAsrSampleRate());
        provider.setAsrEnablePunctuation(request.getAsrEnablePunctuation() == null
            ? (create || Boolean.TRUE.equals(provider.getAsrEnablePunctuation())) : request.getAsrEnablePunctuation());
        provider.setAsrEnableItn(request.getAsrEnableItn() == null
            ? (create || Boolean.TRUE.equals(provider.getAsrEnableItn())) : request.getAsrEnableItn());
        provider.setAsrEnableIntermediateResult(request.getAsrEnableIntermediateResult() == null
            ? (!create && Boolean.TRUE.equals(provider.getAsrEnableIntermediateResult())) : request.getAsrEnableIntermediateResult());
        provider.setAsrSilenceTimeoutMs(request.getAsrSilenceTimeoutMs() == null
            ? (create ? 800 : provider.getAsrSilenceTimeoutMs()) : request.getAsrSilenceTimeoutMs());
        provider.setAsrMaxSentenceMs(request.getAsrMaxSentenceMs() == null
            ? (create ? 60000 : provider.getAsrMaxSentenceMs()) : request.getAsrMaxSentenceMs());
        if (create || request.getAsrOptionsJson() != null) provider.setAsrOptionsJson(request.getAsrOptionsJson());
        provider.setEnabled(request.getEnabled() == null ? (create || Boolean.TRUE.equals(provider.getEnabled())) : request.getEnabled());
        provider.setRemark(request.getRemark());
    }

    private void validateProvider(AiSpeechProvider provider) {
        if (!Boolean.TRUE.equals(provider.getTtsEnabled())
            && !Boolean.TRUE.equals(provider.getStreamingTtsEnabled())
            && !Boolean.TRUE.equals(provider.getRecordingAsrEnabled())
            && !Boolean.TRUE.equals(provider.getStreamingAsrEnabled())) {
            throw new ServiceException("语音服务商至少需要启用一种能力");
        }
        if (!Boolean.TRUE.equals(provider.getEnabled()) && isAnyDefault(provider)) {
            throw new ServiceException("停用语音服务商前，请先指定其他默认服务商");
        }
        if (Boolean.TRUE.equals(provider.getDefaultTts()) && !Boolean.TRUE.equals(provider.getTtsEnabled())) {
            throw new ServiceException("默认 TTS 服务商必须启用 TTS 能力");
        }
        if (Boolean.TRUE.equals(provider.getDefaultStreamingTts()) && !Boolean.TRUE.equals(provider.getStreamingTtsEnabled())) {
            throw new ServiceException("默认实时 TTS 服务商必须启用实时 TTS 能力");
        }
        if (Boolean.TRUE.equals(provider.getDefaultRecordingAsr()) && !Boolean.TRUE.equals(provider.getRecordingAsrEnabled())) {
            throw new ServiceException("默认录音 ASR 服务商必须启用录音 ASR 能力");
        }
        if (Boolean.TRUE.equals(provider.getDefaultStreamingAsr()) && !Boolean.TRUE.equals(provider.getStreamingAsrEnabled())) {
            throw new ServiceException("默认流式 ASR 服务商必须启用流式 ASR 能力");
        }
        if (Boolean.TRUE.equals(provider.getTtsEnabled())) {
            providerRegistry.get(provider.getProviderType());
        }
        if (Boolean.TRUE.equals(provider.getStreamingTtsEnabled())) {
            streamingTtsProviderRegistry.get(provider.getProviderType());
        }
        if (Boolean.TRUE.equals(provider.getRecordingAsrEnabled())) {
            asrProviderRegistry.get(provider.getProviderType());
        }
        if (Boolean.TRUE.equals(provider.getStreamingAsrEnabled())) {
            streamingAsrProviderRegistry.get(provider.getProviderType());
        }
        if (Boolean.TRUE.equals(provider.getTtsEnabled())
            && !"ALIYUN_NLS".equals(provider.getProviderType())
            && StringUtils.isBlank(provider.getEndpointUrl())) {
            throw new ServiceException("TTS 请求地址不能为空");
        }
    }
    private void validateDefaultMutation(AiSpeechProvider current, AiSpeechProviderRequest request) {
        boolean enabled = request.getEnabled() == null ? Boolean.TRUE.equals(current.getEnabled()) : request.getEnabled();
        boolean ttsEnabled = request.getTtsEnabled() == null ? Boolean.TRUE.equals(current.getTtsEnabled()) : request.getTtsEnabled();
        boolean defaultTts = request.getDefaultTts() == null ? Boolean.TRUE.equals(current.getDefaultTts()) : request.getDefaultTts();
        boolean streamingTtsEnabled = request.getStreamingTtsEnabled() == null
            ? Boolean.TRUE.equals(current.getStreamingTtsEnabled()) : request.getStreamingTtsEnabled();
        boolean defaultStreamingTts = request.getDefaultStreamingTts() == null
            ? Boolean.TRUE.equals(current.getDefaultStreamingTts()) : request.getDefaultStreamingTts();
        boolean recordingAsrEnabled = request.getRecordingAsrEnabled() == null
            ? Boolean.TRUE.equals(current.getRecordingAsrEnabled()) : request.getRecordingAsrEnabled();
        boolean defaultRecordingAsr = request.getDefaultRecordingAsr() == null
            ? Boolean.TRUE.equals(current.getDefaultRecordingAsr()) : request.getDefaultRecordingAsr();
        boolean streamingAsrEnabled = request.getStreamingAsrEnabled() == null
            ? Boolean.TRUE.equals(current.getStreamingAsrEnabled()) : request.getStreamingAsrEnabled();
        boolean defaultStreamingAsr = request.getDefaultStreamingAsr() == null
            ? Boolean.TRUE.equals(current.getDefaultStreamingAsr()) : request.getDefaultStreamingAsr();
        if (Boolean.TRUE.equals(current.getDefaultTts())
            && (!enabled || !ttsEnabled || !defaultTts)) {
            throw new ServiceException("当前服务商是默认 TTS，请先指定其他默认 TTS 服务商");
        }
        if (Boolean.TRUE.equals(current.getDefaultStreamingTts())
            && (!enabled || !streamingTtsEnabled || !defaultStreamingTts)) {
            throw new ServiceException("当前服务商是默认实时 TTS，请先指定其他默认实时 TTS 服务商");
        }
        if (Boolean.TRUE.equals(current.getDefaultRecordingAsr())
            && (!enabled || !recordingAsrEnabled || !defaultRecordingAsr)) {
            throw new ServiceException("当前服务商是默认录音 ASR，请先指定其他默认录音 ASR 服务商");
        }
        if (Boolean.TRUE.equals(current.getDefaultStreamingAsr())
            && (!enabled || !streamingAsrEnabled || !defaultStreamingAsr)) {
            throw new ServiceException("当前服务商是默认流式 ASR，请先指定其他默认流式 ASR 服务商");
        }
    }
    private void clearOtherDefaults(AiSpeechProvider provider, Long excludedId) {
        if (Boolean.TRUE.equals(provider.getDefaultTts())) {
            providerMapper.update(null, new LambdaUpdateWrapper<AiSpeechProvider>()
                .set(AiSpeechProvider::getDefaultTts, false)
                .eq(AiSpeechProvider::getDefaultTts, true)
                .ne(excludedId != null, AiSpeechProvider::getId, excludedId));
        }
        if (Boolean.TRUE.equals(provider.getDefaultStreamingTts())) {
            providerMapper.update(null, new LambdaUpdateWrapper<AiSpeechProvider>()
                .set(AiSpeechProvider::getDefaultStreamingTts, false)
                .eq(AiSpeechProvider::getDefaultStreamingTts, true)
                .ne(excludedId != null, AiSpeechProvider::getId, excludedId));
        }
        if (Boolean.TRUE.equals(provider.getDefaultRecordingAsr())) {
            providerMapper.update(null, new LambdaUpdateWrapper<AiSpeechProvider>()
                .set(AiSpeechProvider::getDefaultRecordingAsr, false)
                .eq(AiSpeechProvider::getDefaultRecordingAsr, true)
                .ne(excludedId != null, AiSpeechProvider::getId, excludedId));
        }
        if (Boolean.TRUE.equals(provider.getDefaultStreamingAsr())) {
            providerMapper.update(null, new LambdaUpdateWrapper<AiSpeechProvider>()
                .set(AiSpeechProvider::getDefaultStreamingAsr, false)
                .eq(AiSpeechProvider::getDefaultStreamingAsr, true)
                .ne(excludedId != null, AiSpeechProvider::getId, excludedId));
        }
    }

    private boolean isAnyDefault(AiSpeechProvider provider) {
        return Boolean.TRUE.equals(provider.getDefaultTts())
            || Boolean.TRUE.equals(provider.getDefaultStreamingTts())
            || Boolean.TRUE.equals(provider.getDefaultRecordingAsr())
            || Boolean.TRUE.equals(provider.getDefaultStreamingAsr());
    }
    private AiSpeechProvider requireEnabledRecordingAsrProvider(Long id) {
        AiSpeechProvider provider = requireProvider(id);
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new ServiceException("语音服务商未启用");
        }
        if (!Boolean.TRUE.equals(provider.getRecordingAsrEnabled())) {
            throw new ServiceException("语音服务商未启用录音 ASR 能力");
        }
        asrProviderRegistry.get(provider.getProviderType());
        return provider;
    }

    private void validateAsrTestFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("ASR 测试音频不能为空");
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new ServiceException("ASR 测试音频不能超过 20MB");
        }
    }

    private String resolveAsrTestFormat(MultipartFile file, String requestedFormat, String configuredFormat) {
        String format = requestedFormat;
        if (StringUtils.isBlank(format) && StringUtils.isNotBlank(file.getOriginalFilename())) {
            String name = file.getOriginalFilename();
            int dot = name.lastIndexOf('.');
            format = dot >= 0 ? name.substring(dot + 1) : null;
        }
        format = StringUtils.blankToDefault(format, configuredFormat);
        format = StringUtils.blankToDefault(format, "wav").replace(".", "").toLowerCase();
        if (!List.of("wav", "pcm", "opus", "opu", "speex").contains(format)) {
            throw new ServiceException("ASR 测试仅支持 WAV、PCM、OPUS、OPU、SPEEX 音频");
        }
        return format;
    }

    private void fillTemplate(AiSpeechTemplate template, AiSpeechTemplateRequest request, boolean create) {
        template.setTemplateCode(request.getTemplateCode());
        template.setTemplateName(request.getTemplateName());
        template.setBusinessType(request.getBusinessType().trim().toUpperCase());
        template.setTemplateText(request.getTemplateText());
        template.setDefaultVoice(request.getDefaultVoice());
        template.setEnabled(request.getEnabled() == null || request.getEnabled());
        template.setRemark(request.getRemark());
    }

    private String chooseVoice(AiSpeechProvider provider, String voice) {
        return StringUtils.isBlank(voice) ? provider.getDefaultVoice() : voice;
    }

    private void taskSanity(String text) {
        if (StringUtils.isBlank(text)) {
            throw new ServiceException("TTS 文本不能为空");
        }
    }

    private AiSpeechProvider requireProvider(Long id) {
        AiSpeechProvider provider = providerMapper.selectById(id);
        if (provider == null) throw new ServiceException("语音服务商不存在");
        return provider;
    }

    private AiSpeechProvider requireEnabledTtsProvider(Long id) {
        AiSpeechProvider provider = requireProvider(id);
        if (!Boolean.TRUE.equals(provider.getEnabled())) throw new ServiceException("语音服务商未启用");
        if (!Boolean.TRUE.equals(provider.getTtsEnabled())) throw new ServiceException("语音服务商未启用 TTS 能力");
        return provider;
    }

    private AiSpeechTemplate requireTemplate(Long id) {
        AiSpeechTemplate template = templateMapper.selectById(id);
        if (template == null) throw new ServiceException("语音模板不存在");
        return template;
    }

    private void ensureProviderCodeUnique(String code, Long excludedId) {
        boolean exists = providerMapper.exists(new LambdaQueryWrapper<AiSpeechProvider>()
            .eq(AiSpeechProvider::getProviderCode, code)
            .ne(excludedId != null, AiSpeechProvider::getId, excludedId));
        if (exists) throw new ServiceException("语音服务商编码已存在");
    }

    private void ensureTemplateCodeUnique(String code, Long excludedId) {
        boolean exists = templateMapper.exists(new LambdaQueryWrapper<AiSpeechTemplate>()
            .eq(AiSpeechTemplate::getTemplateCode, code)
            .ne(excludedId != null, AiSpeechTemplate::getId, excludedId));
        if (exists) throw new ServiceException("语音模板编码已存在");
    }

    private String textHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AiSpeechProviderResponse providerResponse(AiSpeechProvider provider) {
        AiSpeechProviderResponse response = new AiSpeechProviderResponse();
        response.setId(provider.getId());
        response.setProviderCode(provider.getProviderCode());
        response.setProviderName(provider.getProviderName());
        response.setProviderType(provider.getProviderType());
        response.setTtsEnabled(provider.getTtsEnabled());
        response.setStreamingTtsEnabled(provider.getStreamingTtsEnabled());
        response.setRecordingAsrEnabled(provider.getRecordingAsrEnabled());
        response.setStreamingAsrEnabled(provider.getStreamingAsrEnabled());
        response.setDefaultTts(provider.getDefaultTts());
        response.setDefaultStreamingTts(provider.getDefaultStreamingTts());
        response.setDefaultRecordingAsr(provider.getDefaultRecordingAsr());
        response.setDefaultStreamingAsr(provider.getDefaultStreamingAsr());
        response.setEndpointUrl(provider.getEndpointUrl());
        response.setHttpMethod(provider.getHttpMethod());
        response.setAuthType(provider.getAuthType());
        response.setAuthHeaderName(provider.getAuthHeaderName());
        response.setAuthConfigured(StringUtils.isNotBlank(provider.getAuthToken()));
        response.setDefaultVoice(provider.getDefaultVoice());
        response.setDefaultFormat(provider.getDefaultFormat());
        response.setDefaultSampleRate(provider.getDefaultSampleRate());
        response.setTimeoutSeconds(provider.getTimeoutSeconds());
        response.setStreamingTtsEndpointUrl(provider.getStreamingTtsEndpointUrl());
        response.setStreamingTtsOptionsJson(provider.getStreamingTtsOptionsJson());
        response.setRecordingAsrEndpointUrl(provider.getRecordingAsrEndpointUrl());
        response.setStreamingAsrEndpointUrl(provider.getStreamingAsrEndpointUrl());
        response.setAsrLanguage(provider.getAsrLanguage());
        response.setAsrFormat(provider.getAsrFormat());
        response.setAsrSampleRate(provider.getAsrSampleRate());
        response.setAsrEnablePunctuation(provider.getAsrEnablePunctuation());
        response.setAsrEnableItn(provider.getAsrEnableItn());
        response.setAsrEnableIntermediateResult(provider.getAsrEnableIntermediateResult());
        response.setAsrSilenceTimeoutMs(provider.getAsrSilenceTimeoutMs());
        response.setAsrMaxSentenceMs(provider.getAsrMaxSentenceMs());
        response.setAsrOptionsJson(provider.getAsrOptionsJson());
        response.setEnabled(provider.getEnabled());
        response.setRemark(provider.getRemark());
        response.setVersion(provider.getVersion());
        response.setCreateTime(provider.getCreateTime());
        return response;
    }

    private AiSpeechTemplateResponse templateResponse(AiSpeechTemplate template) {
        AiSpeechTemplateResponse response = new AiSpeechTemplateResponse();
        response.setId(template.getId());
        response.setTemplateCode(template.getTemplateCode());
        response.setTemplateName(template.getTemplateName());
        response.setBusinessType(template.getBusinessType());
        response.setTemplateText(template.getTemplateText());
        response.setDefaultVoice(template.getDefaultVoice());
        response.setEnabled(template.getEnabled());
        response.setRemark(template.getRemark());
        response.setVersion(template.getVersion());
        response.setCreateTime(template.getCreateTime());
        return response;
    }

    private AiSpeechTaskResponse taskResponse(AiSpeechTask task) {
        AiSpeechTaskResponse response = new AiSpeechTaskResponse();
        response.setId(task.getId());
        response.setTaskType(task.getTaskType());
        response.setBusinessType(task.getBusinessType());
        response.setBusinessId(task.getBusinessId());
        response.setProviderId(task.getProviderId());
        response.setProviderType(task.getProviderType());
        response.setVoiceName(task.getVoiceName());
        response.setTextContent(task.getTextContent());
        response.setInputMediaId(task.getInputMediaId());
        response.setOutputMediaId(task.getOutputMediaId());
        response.setStatus(task.getStatus());
        response.setRetryCount(task.getRetryCount());
        response.setFailureReason(task.getFailureReason());
        response.setStartedAt(task.getStartedAt());
        response.setFinishedAt(task.getFinishedAt());
        response.setCreateTime(task.getCreateTime());
        return response;
    }

    private AiGeneratedMediaResponse bindingResponse(AiGeneratedMedia binding, String syncedPath) {
        AiGeneratedMediaResponse response = new AiGeneratedMediaResponse();
        response.setId(binding.getId());
        response.setBusinessType(binding.getBusinessType());
        response.setBusinessId(binding.getBusinessId());
        response.setMediaId(binding.getMediaId());
        response.setTaskId(binding.getTaskId());
        response.setGenerationStatus(binding.getGenerationStatus());
        response.setGeneratedAt(binding.getGeneratedAt());
        response.setFailureReason(binding.getFailureReason());
        response.setSyncedPath(syncedPath);
        return response;
    }

    private AiCallTranscriptResponse transcriptResponse(AiCallTranscript transcript) {
        AiCallTranscriptResponse response = new AiCallTranscriptResponse();
        response.setId(transcript.getId());
        response.setCallSessionId(transcript.getCallSessionId());
        response.setBusinessCallId(transcript.getBusinessCallId());
        response.setProviderId(transcript.getProviderId());
        response.setProviderType(transcript.getProviderType());
        response.setInputMediaId(transcript.getInputMediaId());
        response.setRecordingOssId(transcript.getRecordingOssId());
        response.setStatus(transcript.getStatus());
        response.setFullText(transcript.getFullText());
        response.setFailureReason(transcript.getFailureReason());
        response.setStartedAt(transcript.getStartedAt());
        response.setFinishedAt(transcript.getFinishedAt());
        response.setCreateTime(transcript.getCreateTime());
        List<AiCallTranscriptSegmentResponse> segments = transcriptSegmentMapper.selectList(new LambdaQueryWrapper<AiCallTranscriptSegment>()
                .eq(AiCallTranscriptSegment::getTranscriptId, transcript.getId())
                .orderByAsc(AiCallTranscriptSegment::getStartMs)
                .orderByAsc(AiCallTranscriptSegment::getSentenceIndex))
            .stream().map(this::segmentResponse).toList();
        response.setSegments(segments);
        return response;
    }

    private AiCallTranscriptSegmentResponse segmentResponse(AiCallTranscriptSegment segment) {
        AiCallTranscriptSegmentResponse response = new AiCallTranscriptSegmentResponse();
        response.setId(segment.getId());
        response.setSpeaker(segment.getSpeaker());
        response.setSourceType(segment.getSourceType());
        response.setLegUuid(segment.getLegUuid());
        response.setAgentId(segment.getAgentId());
        response.setSentenceIndex(segment.getSentenceIndex());
        response.setStartMs(segment.getStartMs());
        response.setEndMs(segment.getEndMs());
        response.setMessageTime(segment.getMessageTime());
        response.setTextContent(segment.getTextContent());
        response.setFinalResult(segment.getFinalResult());
        response.setConfidence(segment.getConfidence());
        return response;
    }
}



