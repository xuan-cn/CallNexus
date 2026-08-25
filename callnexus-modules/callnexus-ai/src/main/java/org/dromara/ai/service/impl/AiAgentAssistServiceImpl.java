package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentAssistSession;
import org.dromara.ai.domain.AiAgentAssistSuggestion;
import org.dromara.ai.domain.AiCallTranscriptSegment;
import org.dromara.ai.domain.request.AiAgentAssistSegmentRequest;
import org.dromara.ai.domain.response.AiAgentAssistDetailResponse;
import org.dromara.ai.domain.response.AiAgentAssistSuggestionResponse;
import org.dromara.ai.domain.response.AiCallTranscriptSegmentResponse;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.mapper.AiAgentAssistSessionMapper;
import org.dromara.ai.mapper.AiAgentAssistSuggestionMapper;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiCallTranscriptSegmentMapper;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.service.AiAgentAssistService;
import org.dromara.ai.service.AiAgentAssistStreamService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiAgentAssistServiceImpl implements AiAgentAssistService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int CONTEXT_SEGMENT_LIMIT = 8;

    private final AiAgentAssistSessionMapper sessionMapper;
    private final AiAgentAssistSuggestionMapper suggestionMapper;
    private final AiCallTranscriptSegmentMapper transcriptSegmentMapper;
    private final AiAgentMapper agentMapper;
    private final AiAgentApplicationService agentApplicationService;
    private final AiAgentAssistStreamService streamService;
    @Resource(name = "aiRealtimeExecutor")
    private Executor executor;
    private final ConcurrentHashMap<String, Object> callLocks = new ConcurrentHashMap<>();

    @Override
    public void accept(AiAgentAssistSegmentRequest request) {
        if (request == null || StringUtils.isAnyBlank(request.tenantId(), request.businessCallId(), request.customerText())
            || request.callSessionId() == null || request.transcriptSegmentId() == null
            || request.skillGroupId() == null || request.assistAgentId() == null) {
            return;
        }
        executor.execute(() -> TenantHelper.dynamic(request.tenantId(), () -> processSerially(request)));
    }

    @Override
    public AiAgentAssistDetailResponse detail(String businessCallId) {
        AiAgentAssistSession session = findSession(businessCallId);
        AiAgentAssistDetailResponse response = new AiAgentAssistDetailResponse();
        response.setBusinessCallId(businessCallId);
        response.setTranscriptSegments(transcriptSegmentMapper.selectList(
                new LambdaQueryWrapper<AiCallTranscriptSegment>()
                    .eq(AiCallTranscriptSegment::getBusinessCallId, businessCallId)
                    .orderByAsc(AiCallTranscriptSegment::getSentenceIndex, AiCallTranscriptSegment::getId))
            .stream().map(this::transcriptResponse).toList());
        if (session == null) {
            return response;
        }
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
        return response;
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

    private AiAgentAssistSession findSession(String businessCallId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<AiAgentAssistSession>()
            .eq(AiAgentAssistSession::getBusinessCallId, businessCallId)
            .orderByDesc(AiAgentAssistSession::getId)
            .last("limit 1"));
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
