package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentAssistSession;
import org.dromara.ai.domain.AiAgentAssistSuggestion;
import org.dromara.ai.domain.AiCallTranscriptSegment;
import org.dromara.ai.domain.AiTicketDraft;
import org.dromara.ai.domain.AiTicketDraftTask;
import org.dromara.ai.domain.AiTicketPolicy;
import org.dromara.ai.domain.AiCallTranscript;
import org.dromara.ai.domain.AiCallRecordingSource;
import org.dromara.ai.domain.request.AiAgentAssistCustomerSummaryRequest;
import org.dromara.ai.domain.request.AiAgentAssistSegmentRequest;
import org.dromara.ai.domain.response.AiAgentAssistDetailResponse;
import org.dromara.ai.domain.response.AiAgentAssistSuggestionResponse;
import org.dromara.ai.domain.response.AiCallTranscriptSegmentResponse;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.domain.response.AiTicketDraftResponse;
import org.dromara.ai.domain.response.AiAgentAssistCustomerSummaryResponse;
import org.dromara.ai.mapper.AiAgentAssistSessionMapper;
import org.dromara.ai.mapper.AiAgentAssistSuggestionMapper;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiCallTranscriptSegmentMapper;
import org.dromara.ai.mapper.AiTicketDraftMapper;
import org.dromara.ai.mapper.AiTicketPolicyMapper;
import org.dromara.ai.mapper.AiCallTranscriptMapper;
import org.dromara.ai.mapper.AiCallRecordingSourceMapper;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.service.AiAgentAssistAvailabilityProvider;
import org.dromara.ai.service.AiAgentAssistService;
import org.dromara.ai.service.AiAgentAssistStreamService;
import org.dromara.ai.service.AiTicketDraftReviewService;
import org.dromara.ai.service.AiTicketBusinessContextProvider;
import org.dromara.ai.domain.request.AiTicketDraftReviewRequest;
import org.dromara.ai.domain.request.AiTicketDraftUpdateRequest;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiAgentAssistServiceImpl implements AiAgentAssistService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String SPEAKER_CUSTOMER = "CUSTOMER";
    private static final int CONTEXT_SEGMENT_LIMIT = 8;

    private final AiAgentAssistSessionMapper sessionMapper;
    private final AiAgentAssistSuggestionMapper suggestionMapper;
    private final AiCallTranscriptSegmentMapper transcriptSegmentMapper;
    private final AiTicketDraftMapper ticketDraftMapper;
    private final AiAgentMapper agentMapper;
    private final AiAgentApplicationService agentApplicationService;
    private final AiAgentAssistStreamService streamService;
    private final AiTicketDraftReviewService ticketDraftReviewService;
    private final AiTicketPolicyMapper ticketPolicyMapper;
    private final AiCallTranscriptMapper transcriptMapper;
    private final AiCallRecordingSourceMapper callSourceMapper;
    private final AiTicketDraftGenerator ticketDraftGenerator;
    private final ObjectProvider<AiTicketBusinessContextProvider> ticketContextProviders;
    private final ObjectProvider<AiAgentAssistAvailabilityProvider> availabilityProviders;
    @Resource(name = "aiRealtimeExecutor")
    private Executor executor;
    private final ConcurrentHashMap<String, Object> callLocks = new ConcurrentHashMap<>();
    private final Set<String> pendingSegments = ConcurrentHashMap.newKeySet();

    @Override
    public void accept(AiAgentAssistSegmentRequest request) {
        if (request == null || StringUtils.isAnyBlank(request.tenantId(), request.businessCallId(), request.customerText())
            || request.callSessionId() == null || request.transcriptSegmentId() == null
            || request.skillGroupId() == null || request.assistAgentId() == null) {
            return;
        }
        String pendingKey = request.tenantId() + ':' + request.transcriptSegmentId();
        if (!pendingSegments.add(pendingKey)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    TenantHelper.dynamic(request.tenantId(), () -> processSerially(request));
                } finally {
                    pendingSegments.remove(pendingKey);
                }
            });
        } catch (RuntimeException exception) {
            pendingSegments.remove(pendingKey);
            throw exception;
        }
    }

    @Override
    public AiAgentAssistDetailResponse detail(String businessCallId) {
        AiAgentAssistSession session = findSession(businessCallId);
        AiAgentAssistAvailabilityProvider provider = availabilityProviders.getIfAvailable();
        AiAgentAssistAvailabilityProvider.Availability availability = provider == null
            ? null
            : provider.resolve(businessCallId);
        AiAgentAssistDetailResponse response = new AiAgentAssistDetailResponse();
        response.setBusinessCallId(businessCallId);
        List<AiCallTranscriptSegment> transcriptSegments = transcriptSegmentMapper.selectList(
            new LambdaQueryWrapper<AiCallTranscriptSegment>()
                .eq(AiCallTranscriptSegment::getBusinessCallId, businessCallId)
                .orderByAsc(AiCallTranscriptSegment::getSentenceIndex, AiCallTranscriptSegment::getId));
        response.setTranscriptSegments(transcriptSegments.stream().map(this::transcriptResponse).toList());
        AiTicketDraft draft = ticketDraftMapper.selectOne(new LambdaQueryWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getSourceCallId, businessCallId)
            .in(AiTicketDraft::getStatus, List.of("PENDING_REVIEW", "LOW_CONFIDENCE", "CREATED"))
            .orderByDesc(AiTicketDraft::getUpdateTime, AiTicketDraft::getId).last("LIMIT 1"));
        response.setTicketDraft(ticketDraftResponse(draft));
        if (session == null) {
            availability = availability == null
                ? AiAgentAssistAvailabilityProvider.Availability.disabled()
                : availability;
            response.setAssistEnabled(availability.enabled());
            response.setSkillGroupId(availability.skillGroupId());
            response.setAssistAgentId(availability.assistAgentId());
            enqueueMissingSuggestions(businessCallId, transcriptSegments, availability);
            return response;
        }
        response.setAssistEnabled(availability == null || availability.enabled());
        response.setSessionId(session.getId());
        response.setCallSessionId(session.getCallSessionId());
        response.setSkillGroupId(session.getSkillGroupId());
        response.setAssistAgentId(session.getAssistAgentId());
        response.setSessionState(session.getSessionState());
        AiAgent agent = agentMapper.selectById(session.getAssistAgentId());
        response.setAssistAgentName(agent == null ? null : agent.getAgentName());
        response.setSuggestions(suggestionMapper.selectList(
                new LambdaQueryWrapper<AiAgentAssistSuggestion>()
                    .eq(AiAgentAssistSuggestion::getSessionId, session.getId())
                    .orderByAsc(AiAgentAssistSuggestion::getId))
            .stream().map(this::suggestionResponse).toList());
        if (Boolean.TRUE.equals(response.getAssistEnabled())) {
            enqueueMissingSuggestions(businessCallId, transcriptSegments,
                new AiAgentAssistAvailabilityProvider.Availability(true, session.getCallSessionId(), session.getAgentId(),
                    session.getSkillGroupId(), session.getAssistAgentId()));
        }
        return response;
    }

    private void enqueueMissingSuggestions(String businessCallId, List<AiCallTranscriptSegment> segments,
                                           AiAgentAssistAvailabilityProvider.Availability availability) {
        if (availability == null || !availability.enabled() || availability.callSessionId() == null
            || availability.skillGroupId() == null || availability.assistAgentId() == null) {
            return;
        }
        List<AiCallTranscriptSegment> customerSegments = segments.stream()
            .filter(item -> SPEAKER_CUSTOMER.equals(item.getSpeaker()))
            .filter(item -> Boolean.TRUE.equals(item.getFinalResult()))
            .filter(item -> StringUtils.isNotBlank(item.getTextContent()))
            .toList();
        if (customerSegments.isEmpty()) {
            return;
        }
        Set<Long> existingSegmentIds = new HashSet<>(suggestionMapper.selectList(
                new LambdaQueryWrapper<AiAgentAssistSuggestion>()
                    .in(AiAgentAssistSuggestion::getTranscriptSegmentId,
                        customerSegments.stream().map(AiCallTranscriptSegment::getId).toList()))
            .stream().map(AiAgentAssistSuggestion::getTranscriptSegmentId).toList());
        String tenantId = TenantHelper.getTenantId();
        customerSegments.stream()
            .filter(item -> !existingSegmentIds.contains(item.getId()))
            .forEach(item -> accept(new AiAgentAssistSegmentRequest(
                tenantId, availability.callSessionId(), businessCallId, item.getId(), item.getTextContent(),
                availability.agentId(), availability.skillGroupId(), availability.assistAgentId())));
    }

    private AiTicketDraftResponse ticketDraftResponse(AiTicketDraft value) {
        if (value == null) return null;
        AiTicketDraftResponse response = new AiTicketDraftResponse();
        response.setId(value.getId()); response.setSourceCallId(value.getSourceCallId());
        response.setCustomerId(value.getCustomerId()); response.setCallerNumber(value.getCallerNumber());
        response.setTicketTemplateId(value.getTicketTemplateId()); response.setStatus(value.getStatus());
        response.setConfidence(value.getConfidence()); response.setTitle(value.getTitle()); response.setSummary(value.getSummary());
        response.setFormData(jsonMap(value.getFormDataJson())); response.setMissingFields(jsonList(value.getMissingFieldsJson()));
        response.setFailureReason(value.getFailureReason()); response.setFormalTicketId(value.getFormalTicketId());
        response.setVersion(value.getVersion());
        return response;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> jsonMap(String json) {
        if (StringUtils.isBlank(json)) return new LinkedHashMap<>();
        try { return JsonUtils.getObjectMapper().readValue(json, LinkedHashMap.class); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<String> jsonList(String json) {
        if (StringUtils.isBlank(json)) return new ArrayList<>();
        try { return JsonUtils.getObjectMapper().readValue(json, ArrayList.class); }
        catch (Exception ignored) { return new ArrayList<>(); }
    }

    @Override
    public void regenerate(String businessCallId, Long suggestionId) {
        AiAgentAssistSession session = requireSession(businessCallId);
        AiAgentAssistSuggestion suggestion = suggestionMapper.selectOne(
            new LambdaQueryWrapper<AiAgentAssistSuggestion>()
                .eq(AiAgentAssistSuggestion::getId, suggestionId)
                .eq(AiAgentAssistSuggestion::getSessionId, session.getId())
                .last("limit 1"));
        if (suggestion == null) {
            throw new ServiceException("坐席辅助建议不存在");
        }
        suggestion.setStatus(STATUS_PROCESSING);
        suggestion.setSuggestedReply(null);
        suggestion.setFailureReason(null);
        suggestion.setProcessingMs(null);
        suggestionMapper.updateById(suggestion);
        streamService.publish(TenantHelper.getTenantId(), businessCallId, suggestionResponse(suggestion));
        String tenantId = TenantHelper.getTenantId();
        executor.execute(() -> TenantHelper.dynamic(tenantId,
            () -> generate(session.getId(), suggestion.getId())));
    }

    @Override
    public AiTicketDraftResponse generateTicketDraft(String businessCallId) {
        AssistContext context = requireAssistContext(businessCallId);
        AiTicketPolicy policy = requireTicketPolicy(context.assistAgentId());
        AiCallTranscript transcript = requireTranscript(businessCallId);
        AiCallRecordingSource source = findCallSource(businessCallId);
        AiTicketDraftTask task = new AiTicketDraftTask();
        task.setPolicyId(policy.getId());
        task.setAiAgentId(context.assistAgentId());
        task.setCallSessionId(source == null ? context.callSessionId() : source.getId());
        task.setBusinessCallId(businessCallId);
        task.setTriggerType("MANUAL_TICKET");
        task.setTranscriptId(transcript.getId());
        task.setTranscriptReady(true);
        task.setCallCompleted(false);
        task.setPromptVersionId(policy.getActivePromptVersionId());
        ticketDraftGenerator.generate(task);
        AiTicketDraft draft = ticketDraftMapper.selectOne(new LambdaQueryWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getPolicyId, policy.getId())
            .eq(AiTicketDraft::getSourceCallId, businessCallId)
            .orderByDesc(AiTicketDraft::getId).last("limit 1"));
        if (draft == null) throw new ServiceException("AI 未生成可用的工单草稿");
        return ticketDraftResponse(draft);
    }

    @Override
    public AiAgentAssistCustomerSummaryResponse summarizeCustomer(
        String businessCallId, AiAgentAssistCustomerSummaryRequest request) {
        AssistContext context = requireAssistContext(businessCallId);
        AiTicketPolicy policy = requireCustomerSummaryPolicy(context.assistAgentId());
        if (!Boolean.TRUE.equals(policy.getCustomerSummaryEnabled())
            || policy.getCustomerSummaryTemplateId() == null
            || StringUtils.isBlank(policy.getCustomerSummaryFieldCode())) {
            throw new ServiceException("当前 AI 助手未配置客户总结回写字段");
        }
        String conversation = transcriptText(businessCallId);
        String summary = ticketDraftGenerator.summarizeCustomer(context.assistAgentId(), conversation);
        AiTicketBusinessContextProvider contextProvider = ticketContextProviders.getIfAvailable();
        if (contextProvider == null) throw new ServiceException("客户资料业务服务未加载");
        boolean written = contextProvider.writeCustomerSummary(request.getCustomerId(),
            policy.getCustomerSummaryTemplateId(), policy.getCustomerSummaryFieldCode(), limit(summary, 4000));
        if (!written) throw new ServiceException("客户总结未写入，请确认客户模板与配置模板一致");
        AiAgentAssistCustomerSummaryResponse response = new AiAgentAssistCustomerSummaryResponse();
        response.setTemplateId(policy.getCustomerSummaryTemplateId());
        response.setFieldCode(policy.getCustomerSummaryFieldCode());
        response.setSummary(limit(summary, 4000));
        return response;
    }

    private AiTicketPolicy requireTicketPolicy(Long assistAgentId) {
        AiTicketPolicy policy = ticketPolicyMapper.selectOne(new LambdaQueryWrapper<AiTicketPolicy>()
            .eq(AiTicketPolicy::getAiAgentId, assistAgentId)
            .eq(AiTicketPolicy::getEnabled, true).last("limit 1"));
        if (policy == null) throw new ServiceException("当前坐席辅助 AI 助手未启用自动工单配置");
        return policy;
    }

    private AiTicketPolicy requireCustomerSummaryPolicy(Long assistAgentId) {
        AiTicketPolicy policy = ticketPolicyMapper.selectOne(new LambdaQueryWrapper<AiTicketPolicy>()
            .eq(AiTicketPolicy::getAiAgentId, assistAgentId).last("limit 1"));
        if (policy == null) throw new ServiceException("当前坐席辅助 AI 助手未配置客户总结");
        return policy;
    }

    private AiCallTranscript requireTranscript(String businessCallId) {
        AiCallTranscript transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getBusinessCallId, businessCallId)
            .eq(AiCallTranscript::getStatus, "SUCCESS")
            .orderByDesc(AiCallTranscript::getId).last("limit 1"));
        if (transcript == null) throw new ServiceException("当前通话还没有可用的实时转写");
        return transcript;
    }

    private AiCallRecordingSource findCallSource(String businessCallId) {
        return callSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
            .eq(AiCallRecordingSource::getBusinessCallId, businessCallId)
            .orderByDesc(AiCallRecordingSource::getId).last("limit 1"));
    }

    private String transcriptText(String businessCallId) {
        List<AiCallTranscriptSegment> segments = transcriptSegmentMapper.selectList(
            new LambdaQueryWrapper<AiCallTranscriptSegment>()
                .eq(AiCallTranscriptSegment::getBusinessCallId, businessCallId)
                .eq(AiCallTranscriptSegment::getFinalResult, true)
                .orderByAsc(AiCallTranscriptSegment::getSentenceIndex, AiCallTranscriptSegment::getId));
        StringBuilder content = new StringBuilder();
        for (AiCallTranscriptSegment segment : segments) {
            if (StringUtils.isBlank(segment.getTextContent())) continue;
            content.append(assistSpeakerLabel(segment.getSpeaker())).append('：')
                .append(segment.getTextContent().trim()).append('\n');
        }
        if (content.isEmpty()) throw new ServiceException("当前通话还没有可总结的对话内容");
        return content.toString();
    }

    private String assistSpeakerLabel(String speaker) {
        if ("CUSTOMER".equals(speaker) || "USER".equals(speaker)) return "客户";
        if ("AGENT".equals(speaker)) return "坐席";
        if ("AI".equals(speaker) || "ASSISTANT".equals(speaker)) return "AI";
        return "通话方";
    }

    @Override
    public Long approveTicketDraft(String businessCallId, Long draftId, Integer version) {
        requireTicketDraft(businessCallId, draftId);
        AiTicketDraftReviewRequest request = new AiTicketDraftReviewRequest();
        request.setVersion(version);
        request.setReason("坐席工作台确认");
        return ticketDraftReviewService.approve(draftId, request);
    }

    @Override
    public AiTicketDraftResponse updateTicketDraft(String businessCallId, Long draftId,
                                                    AiTicketDraftUpdateRequest request) {
        requireTicketDraft(businessCallId, draftId);
        ticketDraftReviewService.update(draftId, request);
        AiTicketDraftResponse response = ticketDraftReviewService.get(draftId);
        streamService.publishTicketDraft(TenantHelper.getTenantId(), businessCallId, response);
        return response;
    }

    private AiTicketDraft requireTicketDraft(String businessCallId, Long draftId) {
        AiTicketDraft draft = ticketDraftMapper.selectById(draftId);
        if (draft == null || !businessCallId.equals(draft.getSourceCallId())) {
            throw new ServiceException("当前通话的 AI 工单草稿不存在");
        }
        return draft;
    }

    private void processSerially(AiAgentAssistSegmentRequest request) {
        String lockKey = request.tenantId() + ':' + request.businessCallId();
        Object lock = callLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            try {
                if (suggestionMapper.exists(new LambdaQueryWrapper<AiAgentAssistSuggestion>()
                    .eq(AiAgentAssistSuggestion::getTranscriptSegmentId, request.transcriptSegmentId()))) {
                    return;
                }
                AiAgent agent = agentMapper.selectById(request.assistAgentId());
                if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) {
                    log.warn("Skip agent assist because AI agent is unavailable, businessCallId={}, assistAgentId={}",
                        request.businessCallId(), request.assistAgentId());
                    return;
                }
                AiAgentAssistSession session = ensureSession(request);
                AiAgentAssistSuggestion suggestion = new AiAgentAssistSuggestion();
                suggestion.setSessionId(session.getId());
                suggestion.setTranscriptSegmentId(request.transcriptSegmentId());
                suggestion.setCustomerText(request.customerText().trim());
                suggestion.setStatus(STATUS_PROCESSING);
                suggestionMapper.insert(suggestion);
                streamService.publish(request.tenantId(), request.businessCallId(), suggestionResponse(suggestion));
                generate(session.getId(), suggestion.getId());
            } finally {
                callLocks.remove(lockKey, lock);
            }
        }
    }

    private void generate(Long sessionId, Long suggestionId) {
        AiAgentAssistSession session = sessionMapper.selectById(sessionId);
        AiAgentAssistSuggestion suggestion = suggestionMapper.selectById(suggestionId);
        if (session == null || suggestion == null) {
            return;
        }
        long started = System.currentTimeMillis();
        try {
            AiChatTurnResult result = agentApplicationService.chatOnce(
                session.getAssistAgentId(), session.getConversationId(), buildPrompt(session, suggestion));
            session.setConversationId(result.conversationId());
            sessionMapper.updateById(session);
            suggestion.setSuggestedReply(result.answer().trim());
            suggestion.setSourceType(result.sourceType());
            suggestion.setStatus(STATUS_COMPLETED);
            suggestion.setFailureReason(null);
        } catch (Exception exception) {
            suggestion.setStatus(STATUS_FAILED);
            suggestion.setFailureReason(limit(exception.getMessage(), 500));
            log.warn("Agent assist suggestion failed, businessCallId={}, segmentId={}, error={}",
                session.getBusinessCallId(), suggestion.getTranscriptSegmentId(), exception.getMessage());
        }
        suggestion.setProcessingMs(System.currentTimeMillis() - started);
        suggestionMapper.updateById(suggestion);
        streamService.publish(TenantHelper.getTenantId(), session.getBusinessCallId(), suggestionResponse(suggestion));
    }

    private String buildPrompt(AiAgentAssistSession session, AiAgentAssistSuggestion suggestion) {
        List<AiCallTranscriptSegment> segments = transcriptSegmentMapper.selectList(
            new LambdaQueryWrapper<AiCallTranscriptSegment>()
                .eq(AiCallTranscriptSegment::getCallSessionId, session.getCallSessionId())
                .le(AiCallTranscriptSegment::getId, suggestion.getTranscriptSegmentId())
                .orderByDesc(AiCallTranscriptSegment::getSentenceIndex, AiCallTranscriptSegment::getId)
                .last("limit " + CONTEXT_SEGMENT_LIMIT));
        Collections.reverse(segments);
        StringBuilder context = new StringBuilder();
        for (AiCallTranscriptSegment segment : segments) {
            String label = switch (StringUtils.defaultString(segment.getSpeaker())) {
                case "CUSTOMER" -> "客户";
                case "AGENT" -> "坐席";
                case "AI" -> "AI";
                default -> "通话方";
            };
            context.append(label).append("：").append(segment.getTextContent()).append('\n');
        }
        return """
            你正在为人工坐席提供实时通话辅助。请结合已绑定知识库和最近对话，生成一条坐席现在可以直接对客户说的建议回复。
            要求：准确、简洁、自然；不得编造；不要解释生成过程；不要使用 Markdown；只输出建议回复正文。

            最近对话：
            %s
            当前客户原话：%s
            """.formatted(context, suggestion.getCustomerText());
    }

    private AiAgentAssistSession ensureSession(AiAgentAssistSegmentRequest request) {
        AiAgentAssistSession session = findSession(request.businessCallId());
        if (session != null) {
            session.setAgentId(request.agentId());
            session.setSkillGroupId(request.skillGroupId());
            session.setAssistAgentId(request.assistAgentId());
            session.setSessionState("ACTIVE");
            sessionMapper.updateById(session);
            return session;
        }
        session = new AiAgentAssistSession();
        session.setCallSessionId(request.callSessionId());
        session.setBusinessCallId(request.businessCallId());
        session.setAgentId(request.agentId());
        session.setSkillGroupId(request.skillGroupId());
        session.setAssistAgentId(request.assistAgentId());
        session.setSessionState("ACTIVE");
        session.setStartedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    private AiAgentAssistSession requireSession(String businessCallId) {
        AiAgentAssistSession session = findSession(businessCallId);
        if (session == null) {
            throw new ServiceException("当前通话尚未产生坐席辅助会话");
        }
        return session;
    }

    private AssistContext requireAssistContext(String businessCallId) {
        AiAgentAssistSession session = findSession(businessCallId);
        if (session != null) {
            return new AssistContext(session.getCallSessionId(), session.getAgentId(),
                session.getSkillGroupId(), session.getAssistAgentId());
        }
        AiAgentAssistAvailabilityProvider provider = availabilityProviders.getIfAvailable();
        AiAgentAssistAvailabilityProvider.Availability availability = provider == null
            ? AiAgentAssistAvailabilityProvider.Availability.disabled()
            : provider.resolve(businessCallId);
        if (!availability.enabled() || availability.callSessionId() == null
            || availability.assistAgentId() == null) {
            throw new ServiceException("当前通话未启用坐席辅助");
        }
        return new AssistContext(availability.callSessionId(), availability.agentId(),
            availability.skillGroupId(), availability.assistAgentId());
    }

    private AiAgentAssistSession findSession(String businessCallId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<AiAgentAssistSession>()
            .eq(AiAgentAssistSession::getBusinessCallId, businessCallId)
            .orderByDesc(AiAgentAssistSession::getId)
            .last("limit 1"));
    }

    private record AssistContext(Long callSessionId, Long agentId, Long skillGroupId, Long assistAgentId) {
    }

    private AiAgentAssistSuggestionResponse suggestionResponse(AiAgentAssistSuggestion item) {
        AiAgentAssistSuggestionResponse response = new AiAgentAssistSuggestionResponse();
        response.setId(item.getId());
        response.setTranscriptSegmentId(item.getTranscriptSegmentId());
        response.setCustomerText(item.getCustomerText());
        response.setSuggestedReply(item.getSuggestedReply());
        response.setSourceType(item.getSourceType());
        response.setStatus(item.getStatus());
        response.setFailureReason(item.getFailureReason());
        response.setProcessingMs(item.getProcessingMs());
        response.setCreateTime(item.getCreateTime());
        return response;
    }

    private AiCallTranscriptSegmentResponse transcriptResponse(AiCallTranscriptSegment segment) {
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

    private String limit(String value, int maxLength) {
        if (StringUtils.isBlank(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
