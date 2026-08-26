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
import org.dromara.ai.speech.definition.CapabilityDefinition;
import org.dromara.ai.speech.definition.EndpointMode;
import org.dromara.ai.speech.definition.FieldDefinition;
import org.dromara.ai.speech.definition.SpeechCapability;
import org.dromara.ai.speech.definition.SpeechProviderDefinition;
import org.dromara.ai.speech.definition.SpeechProviderDefinitionRegistry;
import org.dromara.ai.speech.definition.VoiceDefinition;
import org.dromara.ai.support.ByteArrayAudioMultipartFile;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.OssService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
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

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Date;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    private static final String SPEAKER_AGENT = "AGENT";
    private static final String SPEAKER_CUSTOMER = "CUSTOMER";
    private static final String SOURCE_RECORDING_ASR = "RECORDING_ASR";
    private static final String DEFAULT_AGENT_TEMPLATE = "工号{extension}为您服务";
    private static final Duration RECORDING_DOWNLOAD_TTL = Duration.ofHours(2);
    private static final Duration PROVIDER_CATALOG_TTL = Duration.ofMinutes(10);
    private static final String PROVIDER_CATALOG_VERSION = "2026.08.1";

    private final Map<String, CachedProviderCatalog> providerCatalogCache = new ConcurrentHashMap<>();

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
    private final SpeechProviderDefinitionRegistry definitionRegistry;
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
        AiSpeechProvider provider = new AiSpeechProvider();
        provider.setProviderCode(generateProviderCode());
        fillProvider(provider, request, true);
        validateProvider(provider);
        clearOtherDefaults(provider, null);
        providerMapper.insert(provider);
        return provider.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProvider(Long id, AiSpeechProviderRequest request) {
        AiSpeechProvider provider = requireProvider(id);
        if (StringUtils.isNotBlank(request.getProviderType())
            && !provider.getProviderType().equalsIgnoreCase(request.getProviderType())) {
            throw new ServiceException("语音服务商类型创建后不能修改");
        }
        validateDefaultMutation(provider, request);
        fillProvider(provider, request, false);
        validateProvider(provider);
        clearOtherDefaults(provider, id);
        provider.setVersion(request.getVersion());
        if (providerMapper.updateById(provider) != 1) {
            throw new ServiceException("语音服务商已被其他用户修改，请刷新后重试");
        }
        providerCatalogCache.remove(providerCatalogKey(id));
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
        providerCatalogCache.remove(providerCatalogKey(id));
    }

    @Override
    public TtsTestResponse testProvider(Long id, TtsTestRequest request) {
        AiSpeechProvider provider = requireEnabledTtsProvider(id);
        try {
            TtsGenerateResult result = generateAudio(provider, request.getText(), chooseVoice(provider, request.getVoice()), "TTS_TEST", Map.of());
            if (result == null || result.audioBytes() == null || result.audioBytes().length == 0) {
                throw new ServiceException("TTS 服务未返回音频内容");
            }
            String contentType = StringUtils.isBlank(result.contentType()) ? "audio/wav" : result.contentType();
            String playbackUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(result.audioBytes());
            recordTest(provider.getId(), STATUS_SUCCESS, "普通 TTS 合成测试成功");
            return new TtsTestResponse(null, playbackUrl);
        } catch (Exception exception) {
            String message = userTestMessage("普通 TTS 测试失败", exception);
            recordTest(provider.getId(), STATUS_FAILED, message);
            if (exception instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException(message);
        }
    }

    @Override
    public List<String> providerVoices(Long id) {
        SpeechCapabilityCatalogResponse tts = providerCatalog(id, false).capabilities().get(SpeechCapability.TTS);
        if (tts == null || tts.voices().isEmpty()) {
            throw new ServiceException("当前语音服务商没有可用的音色目录，可直接输入厂商支持的音色名称");
        }
        return tts.voices().stream().map(VoiceDefinition::id).toList();
    }

    @Override
    public SpeechProviderCatalogResponse providerCatalog(Long id, boolean refresh) {
        String cacheKey = providerCatalogKey(id);
        CachedProviderCatalog cached = providerCatalogCache.get(cacheKey);
        if (!refresh && cached != null && !cached.expired()) {
            return cached.response();
        }

        AiSpeechProvider provider = requireProvider(id);
        SpeechProviderDefinition definition = definitionRegistry.get(provider.getProviderType());
        Map<SpeechCapability, SpeechCapabilityCatalogResponse> capabilities = new EnumMap<>(SpeechCapability.class);
        definition.capabilities().forEach((capability, item) -> {
            if (!item.supported()) {
                return;
            }
            LinkedHashSet<VoiceDefinition> voices = new LinkedHashSet<>();
            item.models().forEach(model -> voices.addAll(model.voices()));
            capabilities.put(capability, new SpeechCapabilityCatalogResponse(item.models(), List.copyOf(voices)));
        });

        String source = "BUILT_IN";
        String message = "使用系统内置模型和音色目录";
        try {
            CapabilityDefinition ttsDefinition = definition.capabilities().get(SpeechCapability.TTS);
            if (ttsDefinition != null && ttsDefinition.supported()
                && providerRegistry.get(provider.getProviderType()) instanceof TtsVoiceCatalogProvider catalogProvider) {
                List<VoiceDefinition> dynamicVoices = catalogProvider.voices(provider).stream()
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .map(value -> new VoiceDefinition(value, value, false))
                    .toList();
                if (!dynamicVoices.isEmpty()) {
                    mergeDynamicVoices(capabilities, SpeechCapability.TTS, dynamicVoices);
                    mergeDynamicVoices(capabilities, SpeechCapability.STREAMING_TTS, dynamicVoices);
                    source = "DYNAMIC";
                    message = "已从服务商刷新音色目录，模型目录由系统维护";
                }
            }
        } catch (Exception exception) {
            log.warn("刷新语音服务商目录失败，使用内置目录，providerId={}，providerType={}，error={}",
                id, provider.getProviderType(), rootCauseMessage(exception));
            message = "动态目录读取失败，已回退系统内置目录：" + rootCauseMessage(exception);
        }

        LocalDateTime refreshedAt = LocalDateTime.now();
        SpeechProviderCatalogResponse response = new SpeechProviderCatalogResponse(id, provider.getProviderType(),
            PROVIDER_CATALOG_VERSION, source, refreshedAt, capabilities, message);
        providerCatalogCache.put(cacheKey, new CachedProviderCatalog(response, refreshedAt.plus(PROVIDER_CATALOG_TTL)));
        return response;
    }

    private void mergeDynamicVoices(Map<SpeechCapability, SpeechCapabilityCatalogResponse> capabilities,
                                    SpeechCapability capability, List<VoiceDefinition> voices) {
        SpeechCapabilityCatalogResponse current = capabilities.get(capability);
        if (current != null) {
            capabilities.put(capability, new SpeechCapabilityCatalogResponse(current.models(), voices));
        }
    }

    private String providerCatalogKey(Long providerId) {
        return TenantHelper.getTenantId() + ':' + providerId;
    }

    private record CachedProviderCatalog(SpeechProviderCatalogResponse response, LocalDateTime expiresAt) {
        private boolean expired() {
            return expiresAt.isBefore(LocalDateTime.now());
        }
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
            recordTest(provider.getId(), STATUS_SUCCESS, "录音 ASR 识别测试成功");
            return new AsrTestResponse(result.fullText(), result.segments());
        } catch (Exception exception) {
            String message = userTestMessage("录音 ASR 测试失败", exception);
            recordTest(provider.getId(), STATUS_FAILED, message);
            if (exception instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException(message);
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
            byte[] recordingBytes = downloadRecording(source.getRecordingOssId());
            AsrProvider asrProvider = asrProviderRegistry.get(provider.getProviderType());
            AsrTranscribeResult result;
            List<TranscriptChannelClip> channelClips = stereoChannelClips(source, recordingBytes);
            if (!channelClips.isEmpty()) {
                List<TranscriptSegmentDraft> drafts = new ArrayList<>();
                for (TranscriptChannelClip channelClip : channelClips) {
                    AsrTranscribeResult channelResult = asrProvider.transcribe(provider,
                        new AsrTranscribeRequest(channelClip.audioBytes(), "wav", channelClip.sampleRate(),
                            BUSINESS_CALL_TRANSCRIPT, Map.of(
                            "callSessionId", callSessionId,
                            "businessCallId", source.getBusinessCallId(),
                            "trimStartMs", channelClip.offsetMs(),
                            "channel", channelClip.channelName(),
                            "speaker", channelClip.speaker()
                        )));
                    drafts.addAll(segmentDrafts(channelResult, channelClip));
                }
                result = saveStereoTranscriptSuccess(transcript, drafts);
                log.info("通话录音双声道转写完成，callSessionId={}，businessCallId={}，providerCode={}，segments={}，channels={}",
                    source.getId(), source.getBusinessCallId(), provider.getProviderCode(), drafts.size(), channelClips.size());
            } else {
                AudioClip audioClip = asrAudioClip(source, recordingBytes);
                result = asrProvider.transcribe(provider,
                    new AsrTranscribeRequest(audioClip.audioBytes(), recordingFormat(source), provider.getAsrSampleRate(),
                        BUSINESS_CALL_TRANSCRIPT, Map.of(
                        "callSessionId", callSessionId,
                        "businessCallId", source.getBusinessCallId(),
                        "trimStartMs", audioClip.offsetMs()
                    )));
                saveTranscriptSuccess(transcript, result, audioClip.offsetMs(), SPEAKER_UNKNOWN, SOURCE_RECORDING_ASR, null, null);
            }
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

    private AsrTranscribeResult saveStereoTranscriptSuccess(AiCallTranscript transcript, List<TranscriptSegmentDraft> drafts) {
        List<TranscriptSegmentDraft> ordered = drafts.stream()
            .filter(draft -> StringUtils.isNotBlank(draft.text()))
            .sorted(Comparator
                .comparing(TranscriptSegmentDraft::startMs, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(TranscriptSegmentDraft::channelOrder)
                .thenComparing(TranscriptSegmentDraft::sentenceIndex, Comparator.nullsLast(Integer::compareTo)))
            .toList();
        String fullText = stereoFullText(ordered);
        transcript.setStatus(STATUS_SUCCESS);
        transcript.setFullText(fullText);
        transcript.setFailureReason(null);
        transcript.setFinishedAt(LocalDateTime.now());
        transcriptMapper.updateById(transcript);
        int index = 0;
        for (TranscriptSegmentDraft draft : ordered) {
            AiCallTranscriptSegment entity = new AiCallTranscriptSegment();
            entity.setTranscriptId(transcript.getId());
            entity.setCallSessionId(transcript.getCallSessionId());
            entity.setBusinessCallId(transcript.getBusinessCallId());
            entity.setSpeaker(StringUtils.blankToDefault(draft.speaker(), SPEAKER_UNKNOWN));
            entity.setSourceType(SOURCE_RECORDING_ASR);
            entity.setLegUuid(draft.legUuid());
            entity.setAgentId(draft.agentId());
            entity.setSentenceIndex(index++);
            entity.setStartMs(draft.startMs());
            entity.setEndMs(draft.endMs());
            entity.setMessageTime(resolveMessageTime(transcript.getStartedAt(), entity.getStartMs()));
            entity.setTextContent(draft.text());
            entity.setFinalResult(draft.finalResult());
            entity.setConfidence(draft.confidence());
            transcriptSegmentMapper.insert(entity);
        }
        return new AsrTranscribeResult(fullText, ordered.stream()
            .map(draft -> new AsrSegment(draft.sentenceIndex(), draft.startMs(), draft.endMs(), draft.text(),
                draft.confidence(), draft.finalResult()))
            .toList());
    }

    private String stereoFullText(List<TranscriptSegmentDraft> drafts) {
        StringBuilder builder = new StringBuilder();
        for (TranscriptSegmentDraft draft : drafts) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(speakerLabel(draft.speaker())).append("：").append(draft.text().trim());
        }
        return builder.toString();
    }

    private String speakerLabel(String speaker) {
        return switch (StringUtils.blankToDefault(speaker, SPEAKER_UNKNOWN)) {
            case SPEAKER_AGENT -> "坐席";
            case SPEAKER_CUSTOMER -> "客户";
            default -> "未知";
        };
    }

    private List<TranscriptSegmentDraft> segmentDrafts(AsrTranscribeResult result, TranscriptChannelClip channelClip) {
        List<AsrSegment> segments = result == null ? null : result.segments();
        if ((segments == null || segments.isEmpty()) && result != null && StringUtils.isNotBlank(result.fullText())) {
            segments = List.of(new AsrSegment(0, null, null, result.fullText(), null, true));
        }
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        List<TranscriptSegmentDraft> drafts = new ArrayList<>();
        for (AsrSegment segment : segments) {
            if (StringUtils.isBlank(segment.text())) {
                continue;
            }
            drafts.add(new TranscriptSegmentDraft(
                channelClip.channelOrder(),
                segment.sentenceIndex(),
                offsetTime(segment.startMs(), channelClip.offsetMs()),
                offsetTime(segment.endMs(), channelClip.offsetMs()),
                segment.text(),
                segment.confidence(),
                segment.finalResult(),
                channelClip.speaker(),
                channelClip.legUuid(),
                channelClip.agentId()
            ));
        }
        return drafts;
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

    private List<TranscriptChannelClip> stereoChannelClips(AiCallRecordingSource source, byte[] audioBytes) {
        if (!"wav".equalsIgnoreCase(recordingFormat(source))) {
            return List.of();
        }
        int trimStartMs = resolveTrimStartMs(source);
        try (AudioInputStream input = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioBytes))) {
            AudioFormat format = input.getFormat();
            if (format.getChannels() != 2 || format.getSampleSizeInBits() != 16
                || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                return List.of();
            }
            int frameSize = format.getFrameSize();
            if (frameSize != 4) {
                return List.of();
            }
            byte[] stereoPcm = input.readAllBytes();
            int totalFrames = stereoPcm.length / frameSize;
            int trimFrames = Math.min(totalFrames, Math.max(0, Math.round(format.getSampleRate() * trimStartMs / 1000F)));
            int remainingFrames = totalFrames - trimFrames;
            if (remainingFrames <= 0) {
                return List.of();
            }
            byte[] left = new byte[remainingFrames * 2];
            byte[] right = new byte[remainingFrames * 2];
            int sourceOffset = trimFrames * frameSize;
            for (int frame = 0; frame < remainingFrames; frame++) {
                int src = sourceOffset + frame * frameSize;
                int dst = frame * 2;
                left[dst] = stereoPcm[src];
                left[dst + 1] = stereoPcm[src + 1];
                right[dst] = stereoPcm[src + 2];
                right[dst + 1] = stereoPcm[src + 3];
            }
            AudioFormat monoFormat = new AudioFormat(format.getEncoding(), format.getSampleRate(), 16, 1, 2,
                format.getFrameRate(), format.isBigEndian());
            StereoSpeakerMapping mapping = stereoSpeakerMapping(source);
            int sampleRate = Math.round(format.getSampleRate());
            List<TranscriptChannelClip> clips = List.of(
                new TranscriptChannelClip(toWav(left, monoFormat, remainingFrames), trimStartMs, sampleRate,
                    mapping.leftSpeaker(), mapping.leftLegUuid(), mapping.leftAgentId(), "left", 0),
                new TranscriptChannelClip(toWav(right, monoFormat, remainingFrames), trimStartMs, sampleRate,
                    mapping.rightSpeaker(), mapping.rightLegUuid(), mapping.rightAgentId(), "right", 1)
            );
            log.info("通话录音识别为双声道 WAV，callSessionId={}，businessCallId={}，trimStartMs={}，sampleRate={}，frames={}",
                source.getId(), source.getBusinessCallId(), trimStartMs, sampleRate, remainingFrames);
            return clips;
        } catch (Exception exception) {
            log.warn("通话录音双声道拆分失败，降级整段识别，callSessionId={}，businessCallId={}，error={}",
                source.getId(), source.getBusinessCallId(), exception.getMessage());
            return List.of();
        }
    }

    private byte[] toWav(byte[] pcm, AudioFormat format, int frames) throws Exception {
        try (ByteArrayInputStream input = new ByteArrayInputStream(pcm);
             AudioInputStream audioInputStream = new AudioInputStream(input, format, frames);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, output);
            return output.toByteArray();
        }
    }

    private StereoSpeakerMapping stereoSpeakerMapping(AiCallRecordingSource source) {
        Long agentId = source.getOwnerAgentId() == null ? source.getAgentId() : source.getOwnerAgentId();
        String agentLegUuid = source.getOwnerAgentLegUuid();
        if ("INBOUND".equalsIgnoreCase(source.getDirection()) && agentId != null) {
            return new StereoSpeakerMapping(SPEAKER_CUSTOMER, null, null, SPEAKER_AGENT, agentLegUuid, agentId);
        }
        return new StereoSpeakerMapping(SPEAKER_UNKNOWN, null, null, SPEAKER_UNKNOWN, null, null);
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

    private record TranscriptChannelClip(byte[] audioBytes, int offsetMs, int sampleRate, String speaker,
                                         String legUuid, Long agentId, String channelName, int channelOrder) {
    }

    private record StereoSpeakerMapping(String leftSpeaker, String leftLegUuid, Long leftAgentId,
                                        String rightSpeaker, String rightLegUuid, Long rightAgentId) {
    }

    private record TranscriptSegmentDraft(int channelOrder, Integer sentenceIndex, Integer startMs, Integer endMs,
                                          String text, java.math.BigDecimal confidence, boolean finalResult,
                                          String speaker, String legUuid, Long agentId) {
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
        provider.setProviderName(request.getProviderName());
        if (create) {
            provider.setProviderType(request.getProviderType().trim().toUpperCase());
        }
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
        SpeechProviderDefinition definition = definitionRegistry.get(provider.getProviderType());
        Map<String, Object> credentials = mergeCredentials(provider, request.getCredentials());
        applyCredentials(provider, definition, credentials, request.getCredentials(), create);
        provider.setCredentialJson(JsonUtils.toJsonString(nonSecretCredentials(definition, credentials)));
        provider.setConfigurationSchemaVersion(2);
        provider.setTtsModel(defaultModel(request.getTtsModel(), definition, SpeechCapability.TTS));
        provider.setStreamingTtsModel(defaultModel(request.getStreamingTtsModel(), definition, SpeechCapability.STREAMING_TTS));
        provider.setRecordingAsrModel(defaultModel(request.getRecordingAsrModel(), definition, SpeechCapability.RECORDING_ASR));
        provider.setStreamingAsrModel(defaultModel(request.getStreamingAsrModel(), definition, SpeechCapability.STREAMING_ASR));
        provider.setTtsVoice(StringUtils.blankToDefault(request.getTtsVoice(),
            StringUtils.blankToDefault(request.getDefaultVoice(), "default")));
        provider.setStreamingTtsVoice(StringUtils.blankToDefault(request.getStreamingTtsVoice(), provider.getTtsVoice()));
        provider.setDefaultVoice(provider.getTtsVoice());
        provider.setTtsEndpointMode(normalizeMode(request.getTtsEndpointMode()));
        provider.setStreamingTtsEndpointMode(normalizeMode(request.getStreamingTtsEndpointMode()));
        provider.setRecordingAsrEndpointMode(normalizeMode(request.getRecordingAsrEndpointMode()));
        provider.setStreamingAsrEndpointMode(normalizeMode(request.getStreamingAsrEndpointMode()));
        provider.setEndpointUrl(resolveEndpoint(definition, SpeechCapability.TTS, provider.getTtsEndpointMode(), request.getEndpointUrl(), credentials));
        provider.setHttpMethod(StringUtils.isBlank(request.getHttpMethod()) ? "POST" : request.getHttpMethod().trim().toUpperCase());
        if (StringUtils.isNotBlank(request.getAuthType())) provider.setAuthType(request.getAuthType().trim().toUpperCase());
        if (StringUtils.isNotBlank(request.getAuthHeaderName())) provider.setAuthHeaderName(request.getAuthHeaderName());
        provider.setDefaultFormat(StringUtils.isBlank(request.getDefaultFormat()) ? "wav" : request.getDefaultFormat());
        provider.setDefaultSampleRate(request.getDefaultSampleRate() == null ? 8000 : request.getDefaultSampleRate());
        provider.setTimeoutSeconds(request.getTimeoutSeconds() == null ? 30 : request.getTimeoutSeconds());
        provider.setStreamingTtsEndpointUrl(resolveEndpoint(definition, SpeechCapability.STREAMING_TTS,
            provider.getStreamingTtsEndpointMode(), request.getStreamingTtsEndpointUrl(), credentials));
        if (create || request.getStreamingTtsOptionsJson() != null) provider.setStreamingTtsOptionsJson(request.getStreamingTtsOptionsJson());
        provider.setRecordingAsrEndpointUrl(resolveEndpoint(definition, SpeechCapability.RECORDING_ASR,
            provider.getRecordingAsrEndpointMode(), request.getRecordingAsrEndpointUrl(), credentials));
        provider.setStreamingAsrEndpointUrl(resolveEndpoint(definition, SpeechCapability.STREAMING_ASR,
            provider.getStreamingAsrEndpointMode(), request.getStreamingAsrEndpointUrl(), credentials));
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
        SpeechProviderDefinition definition = definitionRegistry.get(provider.getProviderType());
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
            requireSupported(definition, SpeechCapability.TTS);
            providerRegistry.get(provider.getProviderType());
        }
        if (Boolean.TRUE.equals(provider.getStreamingTtsEnabled())) {
            requireSupported(definition, SpeechCapability.STREAMING_TTS);
            streamingTtsProviderRegistry.get(provider.getProviderType());
        }
        if (Boolean.TRUE.equals(provider.getRecordingAsrEnabled())) {
            requireSupported(definition, SpeechCapability.RECORDING_ASR);
            asrProviderRegistry.get(provider.getProviderType());
        }
        if (Boolean.TRUE.equals(provider.getStreamingAsrEnabled())) {
            requireSupported(definition, SpeechCapability.STREAMING_ASR);
            streamingAsrProviderRegistry.get(provider.getProviderType());
        }
        validateCredentials(provider, definition);
        if (Boolean.TRUE.equals(provider.getTtsEnabled())
            && !"ALIYUN_NLS".equals(provider.getProviderType())
            && StringUtils.isBlank(provider.getEndpointUrl())) {
            throw new ServiceException("TTS 请求地址不能为空");
        }
        if ("FUNASR".equals(provider.getProviderType())) {
            if (!Boolean.TRUE.equals(provider.getRecordingAsrEnabled())) {
                throw new ServiceException("FunASR 必须启用录音 ASR 能力");
            }
            if (Boolean.TRUE.equals(provider.getTtsEnabled())
                || Boolean.TRUE.equals(provider.getStreamingTtsEnabled())
                || Boolean.TRUE.equals(provider.getStreamingAsrEnabled())) {
                throw new ServiceException("当前 FunASR 接入仅支持句级 ASR 能力");
            }
            String endpoint = provider.getRecordingAsrEndpointUrl();
            if (StringUtils.isBlank(endpoint)
                || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
                throw new ServiceException("FunASR HTTP 地址必须以 http:// 或 https:// 开头");
            }
        }
        if ("KOKORO_LOCAL".equals(provider.getProviderType())) {
            if (!Boolean.TRUE.equals(provider.getTtsEnabled())) {
                throw new ServiceException("Kokoro 必须启用 TTS 能力");
            }
            if (Boolean.TRUE.equals(provider.getRecordingAsrEnabled())
                || Boolean.TRUE.equals(provider.getStreamingAsrEnabled())) {
                throw new ServiceException("Kokoro 本地服务仅支持 TTS 能力");
            }
            String endpoint = provider.getEndpointUrl();
            if (StringUtils.isBlank(endpoint)
                || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
                throw new ServiceException("Kokoro 服务地址必须以 http:// 或 https:// 开头");
            }
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

    private Map<String, Object> mergeCredentials(AiSpeechProvider provider, Map<String, Object> submitted) {
        Map<String, Object> merged = new LinkedHashMap<>(storedCredentials(provider));
        if (submitted == null) {
            return merged;
        }
        submitted.forEach((key, value) -> {
            if (value != null && (!(value instanceof String text) || StringUtils.isNotBlank(text))) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> storedCredentials(AiSpeechProvider provider) {
        if (StringUtils.isBlank(provider.getCredentialJson())) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> values = JsonUtils.parseObject(provider.getCredentialJson(), LinkedHashMap.class);
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private Map<String, Object> nonSecretCredentials(SpeechProviderDefinition definition, Map<String, Object> credentials) {
        Set<String> secretKeys = definition.credentialFields().stream()
            .filter(FieldDefinition::secret).map(FieldDefinition::key).collect(Collectors.toSet());
        Map<String, Object> result = new LinkedHashMap<>();
        credentials.forEach((key, value) -> {
            if (!secretKeys.contains(key)) {
                result.put(key, value);
            }
        });
        return result;
    }

    private void applyCredentials(AiSpeechProvider provider, SpeechProviderDefinition definition,
                                  Map<String, Object> merged, Map<String, Object> submitted, boolean create) {
        String secretKey = definition.credentialFields().stream()
            .filter(FieldDefinition::secret).map(FieldDefinition::key).findFirst().orElse(null);
        Object submittedSecret = submitted == null || secretKey == null ? null : submitted.get(secretKey);
        if (submittedSecret instanceof String secret && StringUtils.isNotBlank(secret)) {
            provider.setAuthToken(secret);
        } else if (create) {
            provider.setAuthToken(null);
        }

        if ("ALIYUN_NLS".equals(provider.getProviderType())) {
            provider.setAuthType("HEADER");
            provider.setAuthHeaderName(textValue(merged.get("accessKeyId")));
        } else if (StringUtils.isNotBlank(provider.getAuthToken())) {
            provider.setAuthType("BEARER");
            provider.setAuthHeaderName("Authorization");
        } else {
            provider.setAuthType("NONE");
            provider.setAuthHeaderName(null);
        }
    }

    @Override
    public SpeechProviderTestResponse validateProviderConfiguration(AiSpeechProviderRequest request) {
        long started = System.nanoTime();
        AiSpeechProvider provider = previewProvider(request);
        validateProvider(provider);
        return testResponse("VALIDATION", STATUS_SUCCESS, "配置校验通过", started);
    }

    @Override
    public SpeechProviderTestResponse testProviderConnection(AiSpeechProviderRequest request) {
        long started = System.nanoTime();
        AiSpeechProvider provider = previewProvider(request);
        validateProvider(provider);
        List<String> endpoints = enabledEndpoints(provider).stream().distinct().toList();
        if (endpoints.isEmpty()) {
            throw new ServiceException("当前配置没有可检查的服务地址");
        }
        for (String endpoint : endpoints) {
            testEndpointConnection(endpoint, provider.getTimeoutSeconds());
        }
        return testResponse("PREVIEW_CONNECTION", STATUS_SUCCESS,
            "保存前检查通过，共检查 " + endpoints.size() + " 个服务地址", started);
    }

    @Override
    public SpeechProviderTestResponse testProviderConnection(Long id) {
        AiSpeechProvider provider = requireProvider(id);
        long started = System.nanoTime();
        try {
            List<String> endpoints = enabledEndpoints(provider).stream().distinct().toList();
            if (endpoints.isEmpty()) {
                throw new ServiceException("当前配置没有可检查的服务地址");
            }
            for (String endpoint : endpoints) {
                testEndpointConnection(endpoint, provider.getTimeoutSeconds());
            }
            String message = "连接检查通过，共检查 " + endpoints.size() + " 个服务地址";
            recordTest(provider.getId(), STATUS_SUCCESS, message);
            return testResponse("CONNECTION", STATUS_SUCCESS, message, started);
        } catch (Exception exception) {
            String message = userTestMessage("连接检查失败", exception);
            recordTest(provider.getId(), STATUS_FAILED, message);
            throw new ServiceException(message);
        }
    }

    @Override
    public SpeechProviderTestResponse testStreamingProvider(Long id, SpeechCapability capability) {
        AiSpeechProvider provider = requireProvider(id);
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new ServiceException("语音服务商未启用");
        }
        long started = System.nanoTime();
        try {
            if (capability == SpeechCapability.STREAMING_TTS) {
                testStreamingTtsHandshake(provider);
            } else if (capability == SpeechCapability.STREAMING_ASR) {
                testStreamingAsrHandshake(provider);
            } else {
                throw new ServiceException("流式测试仅支持实时 TTS 或实时 ASR");
            }
            String message = capability == SpeechCapability.STREAMING_TTS
                ? "实时 TTS WebSocket 握手成功" : "实时 ASR WebSocket 握手成功";
            recordTest(provider.getId(), STATUS_SUCCESS, message);
            return testResponse(capability.name(), STATUS_SUCCESS, message, started);
        } catch (Exception exception) {
            String message = userTestMessage("流式协议测试失败", exception);
            recordTest(provider.getId(), STATUS_FAILED, message);
            throw new ServiceException(message);
        }
    }

    private void validateCredentials(AiSpeechProvider provider, SpeechProviderDefinition definition) {
        Map<String, Object> values = storedCredentials(provider);
        for (FieldDefinition field : definition.credentialFields()) {
            if (!field.required()) {
                continue;
            }
            boolean configured = field.secret()
                ? StringUtils.isNotBlank(provider.getAuthToken())
                : StringUtils.isNotBlank(textValue(values.get(field.key())));
            if (!configured) {
                throw new ServiceException(field.label() + "不能为空");
            }
        }
    }

    private Set<String> configuredSecretFields(AiSpeechProvider provider, SpeechProviderDefinition definition) {
        if (StringUtils.isBlank(provider.getAuthToken())) {
            return Set.of();
        }
        return definition.credentialFields().stream().filter(FieldDefinition::secret)
            .map(FieldDefinition::key).collect(Collectors.toUnmodifiableSet());
    }

    private String defaultModel(String requested, SpeechProviderDefinition definition, SpeechCapability capability) {
        if (StringUtils.isNotBlank(requested)) {
            return requested.trim();
        }
        CapabilityDefinition capabilityDefinition = definition.capabilities().get(capability);
        return capabilityDefinition == null || !capabilityDefinition.supported() ? null : capabilityDefinition.defaultModel();
    }

    private String normalizeMode(String mode) {
        try {
            return EndpointMode.from(mode).name();
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("Endpoint 模式只能是 AUTO 或 CUSTOM");
        }
    }

    private String resolveEndpoint(SpeechProviderDefinition definition, SpeechCapability capability,
                                   String mode, String customEndpoint, Map<String, Object> credentials) {
        if (EndpointMode.CUSTOM.name().equals(mode)) {
            return StringUtils.trimToNull(customEndpoint);
        }
        return definition.resolveEndpoint(capability, credentials);
    }

    private void requireSupported(SpeechProviderDefinition definition, SpeechCapability capability) {
        CapabilityDefinition configured = definition.capabilities().get(capability);
        if (configured == null || !configured.supported()) {
            throw new ServiceException(definition.label() + "不支持" + capabilityLabel(capability));
        }
    }

    private String capabilityLabel(SpeechCapability capability) {
        return switch (capability) {
            case TTS -> "语音合成";
            case STREAMING_TTS -> "实时语音合成";
            case RECORDING_ASR -> "录音识别";
            case STREAMING_ASR -> "实时语音识别";
        };
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String chooseVoice(AiSpeechProvider provider, String voice) {
        return StringUtils.isBlank(voice) ? StringUtils.blankToDefault(provider.getTtsVoice(), provider.getDefaultVoice()) : voice;
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

    private AiSpeechProvider previewProvider(AiSpeechProviderRequest request) {
        AiSpeechProvider provider = request.getId() == null ? new AiSpeechProvider() : requireProvider(request.getId());
        if (request.getId() == null) {
            provider.setProviderCode(generateProviderCode());
        } else if (StringUtils.isNotBlank(request.getProviderType())
            && !provider.getProviderType().equalsIgnoreCase(request.getProviderType())) {
            throw new ServiceException("语音服务商类型创建后不能修改");
        }
        fillProvider(provider, request, request.getId() == null);
        return provider;
    }

    private void testStreamingTtsHandshake(AiSpeechProvider provider) {
        if (!Boolean.TRUE.equals(provider.getStreamingTtsEnabled())) {
            throw new ServiceException("语音服务商未启用实时 TTS 能力");
        }
        StreamingTtsListener listener = new StreamingTtsListener() {
            @Override public void onStarted() { }
            @Override public void onAudio(byte[] audioBytes) { }
            @Override public void onCompleted() { }
            @Override public void onError(String message) { log.warn("实时 TTS 握手测试回调：{}", message); }
        };
        try (StreamingTtsSession ignored = streamingTtsProviderRegistry.get(provider.getProviderType()).open(provider,
            new StreamingTtsRequest(provider.getStreamingTtsVoice(), "pcm", provider.getDefaultSampleRate(), Map.of("test", true)), listener)) {
            // Opening the session verifies authentication, model selection and session.update acknowledgement.
        }
    }

    private void testStreamingAsrHandshake(AiSpeechProvider provider) {
        if (!Boolean.TRUE.equals(provider.getStreamingAsrEnabled())) {
            throw new ServiceException("语音服务商未启用实时 ASR 能力");
        }
        StreamingAsrListener listener = new StreamingAsrListener() {
            @Override public void onResult(AsrSegment segment) { }
            @Override public void onCompleted(AsrTranscribeResult result) { }
            @Override public void onError(String message) { log.warn("实时 ASR 握手测试回调：{}", message); }
        };
        try (StreamingAsrSession ignored = streamingAsrProviderRegistry.get(provider.getProviderType()).open(provider,
            new StreamingAsrRequest("pcm", provider.getAsrSampleRate(), provider.getAsrLanguage(), Map.of("test", true)), listener)) {
            // The handshake test intentionally sends no audio; content recognition is tested separately.
        }
    }

    private List<String> enabledEndpoints(AiSpeechProvider provider) {
        List<String> endpoints = new ArrayList<>();
        if (Boolean.TRUE.equals(provider.getTtsEnabled()) && StringUtils.isNotBlank(provider.getEndpointUrl())) {
            endpoints.add(provider.getEndpointUrl());
        }
        if (Boolean.TRUE.equals(provider.getStreamingTtsEnabled()) && StringUtils.isNotBlank(provider.getStreamingTtsEndpointUrl())) {
            endpoints.add(provider.getStreamingTtsEndpointUrl());
        }
        if (Boolean.TRUE.equals(provider.getRecordingAsrEnabled()) && StringUtils.isNotBlank(provider.getRecordingAsrEndpointUrl())) {
            endpoints.add(provider.getRecordingAsrEndpointUrl());
        }
        if (Boolean.TRUE.equals(provider.getStreamingAsrEnabled()) && StringUtils.isNotBlank(provider.getStreamingAsrEndpointUrl())) {
            endpoints.add(provider.getStreamingAsrEndpointUrl());
        }
        return endpoints;
    }

    private void testEndpointConnection(String endpoint, Integer configuredTimeoutSeconds) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception exception) {
            throw new ServiceException("服务地址格式错误：" + endpoint);
        }
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new ServiceException("服务地址缺少主机名：" + endpoint);
        }
        int port = uri.getPort();
        if (port <= 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        int timeoutMs = Math.max(1000, Math.min(10000,
            (configuredTimeoutSeconds == null ? 5 : configuredTimeoutSeconds) * 1000));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
        } catch (Exception exception) {
            throw new ServiceException("无法连接 " + host + ":" + port + "，" + rootCauseMessage(exception));
        }
    }

    private SpeechProviderTestResponse testResponse(String testType, String status, String message, long startedNanos) {
        return new SpeechProviderTestResponse(testType, status, message,
            Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private void recordTest(Long providerId, String status, String message) {
        String summary = StringUtils.blankToDefault(message, status);
        if (summary.length() > 500) {
            summary = summary.substring(0, 500);
        }
        providerMapper.update(null, new LambdaUpdateWrapper<AiSpeechProvider>()
            .set(AiSpeechProvider::getLastTestStatus, status)
            .set(AiSpeechProvider::getLastTestMessage, summary)
            .set(AiSpeechProvider::getLastTestTime, new Date())
            .eq(AiSpeechProvider::getId, providerId));
    }

    private String userTestMessage(String prefix, Throwable exception) {
        return prefix + "：" + rootCauseMessage(exception);
    }

    private String rootCauseMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return StringUtils.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
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

    private String generateProviderCode() {
        return "SP_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
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
        response.setTtsModel(provider.getTtsModel());
        response.setStreamingTtsModel(provider.getStreamingTtsModel());
        response.setRecordingAsrModel(provider.getRecordingAsrModel());
        response.setStreamingAsrModel(provider.getStreamingAsrModel());
        response.setTtsVoice(provider.getTtsVoice());
        response.setStreamingTtsVoice(provider.getStreamingTtsVoice());
        response.setTtsEndpointMode(provider.getTtsEndpointMode());
        response.setStreamingTtsEndpointMode(provider.getStreamingTtsEndpointMode());
        response.setRecordingAsrEndpointMode(provider.getRecordingAsrEndpointMode());
        response.setStreamingAsrEndpointMode(provider.getStreamingAsrEndpointMode());
        SpeechProviderDefinition definition = definitionRegistry.get(provider.getProviderType());
        response.setCredentialValues(nonSecretCredentials(definition, storedCredentials(provider)));
        response.setConfiguredSecretFields(configuredSecretFields(provider, definition));
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
        response.setLastTestStatus(provider.getLastTestStatus());
        response.setLastTestMessage(provider.getLastTestMessage());
        response.setLastTestTime(provider.getLastTestTime());
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



