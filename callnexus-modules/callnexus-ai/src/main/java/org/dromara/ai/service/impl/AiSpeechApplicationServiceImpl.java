package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.AiGeneratedMediaQueryService;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.ai.support.ByteArrayAudioMultipartFile;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.media.domain.MediaAssetCategory;
import org.dromara.resource.media.domain.response.MediaAssetResponse;
import org.dromara.resource.media.service.MediaAssetApplicationService;
import org.dromara.resource.media.service.MediaPublicationService;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroup;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private static final String TASK_TTS = "TTS";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String DEFAULT_AGENT_TEMPLATE = "工号{extension}为您服务";

    private final AiTtsProviderMapper providerMapper;
    private final AiSpeechTemplateMapper templateMapper;
    private final AiSpeechTaskMapper taskMapper;
    private final AiGeneratedMediaMapper generatedMediaMapper;
    private final FreeSwitchNodeGroupMapper nodeGroupMapper;
    private final MediaAssetApplicationService mediaAssetService;
    private final MediaPublicationService mediaPublicationService;
    private final AiGeneratedMediaQueryService generatedMediaQueryService;
    private final TtsProviderRegistry providerRegistry;

    @Override
    public List<AiTtsProviderResponse> providers() {
        return providerMapper.selectList(new LambdaQueryWrapper<AiTtsProvider>().orderByAsc(AiTtsProvider::getProviderCode))
            .stream().map(this::providerResponse).toList();
    }

    @Override
    public Long createProvider(AiTtsProviderRequest request) {
        ensureProviderCodeUnique(request.getProviderCode(), null);
        AiTtsProvider provider = new AiTtsProvider();
        fillProvider(provider, request, true);
        providerMapper.insert(provider);
        return provider.getId();
    }

    @Override
    public void updateProvider(Long id, AiTtsProviderRequest request) {
        ensureProviderCodeUnique(request.getProviderCode(), id);
        AiTtsProvider provider = requireProvider(id);
        fillProvider(provider, request, false);
        provider.setVersion(request.getVersion());
        if (providerMapper.updateById(provider) != 1) {
            throw new ServiceException("TTS Provider 已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    public void deleteProvider(Long id) {
        if (providerMapper.deleteById(id) != 1) {
            throw new ServiceException("TTS Provider 不存在");
        }
    }

    @Override
    public TtsTestResponse testProvider(Long id, TtsTestRequest request) {
        if (id != null) {
            AiTtsProvider provider = requireEnabledProvider(id);
            TtsGenerateResult result = generateAudio(provider, request.getText(), chooseVoice(provider, request.getVoice()), "TTS_TEST", Map.of());
            if (result == null || result.audioBytes() == null || result.audioBytes().length == 0) {
                throw new ServiceException("TTS 服务未返回音频内容");
            }
            String contentType = StringUtils.isBlank(result.contentType()) ? "audio/wav" : result.contentType();
            String playbackUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(result.audioBytes());
            return new TtsTestResponse(null, playbackUrl);
        }
        AiTtsProvider provider = requireEnabledProvider(id);
        TtsGenerateResult result = generateAudio(provider, request.getText(), chooseVoice(provider, request.getVoice()), "TTS_TEST", Map.of());
        Long mediaId = storeGeneratedMedia("TTS测试-" + System.currentTimeMillis(), MediaAssetCategory.USER_MUSIC,
            request.getText(), provider, result, null);
        MediaAssetResponse media = mediaAssetService.get(mediaId);
        return new TtsTestResponse(mediaId, media.getPlaybackUrl());
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
        AiTtsProvider provider = defaultProvider();
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
            Long mediaId = storeGeneratedMedia("坐席工号提示音-" + extension, MediaAssetCategory.AGENT_PROMPT, text, provider, result, task.getId());
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

    private TtsGenerateResult generateAudio(AiTtsProvider provider, String text, String voice, String businessType, Map<String, Object> metadata) {
        taskSanity(text);
        TtsGenerateRequest request = new TtsGenerateRequest(text, voice,
            StringUtils.isBlank(provider.getDefaultFormat()) ? "wav" : provider.getDefaultFormat(),
            provider.getDefaultSampleRate() == null ? 8000 : provider.getDefaultSampleRate(),
            businessType, metadata);
        return providerRegistry.get(provider.getProviderType()).generate(provider, request);
    }

    private Long storeGeneratedMedia(String assetName, MediaAssetCategory category, String text, AiTtsProvider provider,
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

    private AiTtsProvider defaultProvider() {
        AiTtsProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiTtsProvider>()
            .eq(AiTtsProvider::getEnabled, true)
            .orderByAsc(AiTtsProvider::getCreateTime)
            .last("limit 1"));
        if (provider == null) {
            throw new ServiceException("未配置启用的 TTS Provider");
        }
        return provider;
    }

    private AiSpeechTask createTask(String businessType, Long businessId, AiTtsProvider provider, String voice, String text) {
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

    private void fillProvider(AiTtsProvider provider, AiTtsProviderRequest request, boolean create) {
        provider.setProviderCode(request.getProviderCode());
        provider.setProviderName(request.getProviderName());
        provider.setProviderType(request.getProviderType().trim().toUpperCase());
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
        provider.setEnabled(request.getEnabled() == null || request.getEnabled());
        provider.setRemark(request.getRemark());
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

    private String chooseVoice(AiTtsProvider provider, String voice) {
        return StringUtils.isBlank(voice) ? provider.getDefaultVoice() : voice;
    }

    private void taskSanity(String text) {
        if (StringUtils.isBlank(text)) {
            throw new ServiceException("TTS 文本不能为空");
        }
    }

    private AiTtsProvider requireProvider(Long id) {
        AiTtsProvider provider = providerMapper.selectById(id);
        if (provider == null) throw new ServiceException("TTS Provider 不存在");
        return provider;
    }

    private AiTtsProvider requireEnabledProvider(Long id) {
        AiTtsProvider provider = requireProvider(id);
        if (!Boolean.TRUE.equals(provider.getEnabled())) throw new ServiceException("TTS Provider 未启用");
        return provider;
    }

    private AiSpeechTemplate requireTemplate(Long id) {
        AiSpeechTemplate template = templateMapper.selectById(id);
        if (template == null) throw new ServiceException("语音模板不存在");
        return template;
    }

    private void ensureProviderCodeUnique(String code, Long excludedId) {
        boolean exists = providerMapper.exists(new LambdaQueryWrapper<AiTtsProvider>()
            .eq(AiTtsProvider::getProviderCode, code)
            .ne(excludedId != null, AiTtsProvider::getId, excludedId));
        if (exists) throw new ServiceException("TTS Provider 编码已存在");
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

    private AiTtsProviderResponse providerResponse(AiTtsProvider provider) {
        AiTtsProviderResponse response = new AiTtsProviderResponse();
        response.setId(provider.getId());
        response.setProviderCode(provider.getProviderCode());
        response.setProviderName(provider.getProviderName());
        response.setProviderType(provider.getProviderType());
        response.setEndpointUrl(provider.getEndpointUrl());
        response.setHttpMethod(provider.getHttpMethod());
        response.setAuthType(provider.getAuthType());
        response.setAuthHeaderName(provider.getAuthHeaderName());
        response.setDefaultVoice(provider.getDefaultVoice());
        response.setDefaultFormat(provider.getDefaultFormat());
        response.setDefaultSampleRate(provider.getDefaultSampleRate());
        response.setTimeoutSeconds(provider.getTimeoutSeconds());
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
}
