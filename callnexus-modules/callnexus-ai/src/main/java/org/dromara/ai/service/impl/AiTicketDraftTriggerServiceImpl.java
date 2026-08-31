package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.service.AiTicketDraftTriggerService;
import org.dromara.ai.service.model.AiTicketCallCompletedContext;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiTicketDraftTriggerServiceImpl implements AiTicketDraftTriggerService {
    private static final long REALTIME_DEBOUNCE_SECONDS = 5L;
    private final AiRealtimeCallSessionMapper realtimeSessionMapper;
    private final AiTicketPolicyMapper policyMapper;
    private final AiTicketDraftTaskMapper taskMapper;
    private final AiCallTranscriptMapper transcriptMapper;
    private final AiCallRecordingSourceMapper callSourceMapper;
    private final AiTicketDraftTaskDispatcher dispatcher;

    @Override
    public void onCallCompleted(AiTicketCallCompletedContext context) {
        TenantHelper.dynamic(context.tenantId(), () -> markCallCompleted(context));
    }

    @Override
    public void onTranscriptReady(String tenantId, String businessCallId, Long transcriptId) {
        TenantHelper.dynamic(tenantId, () -> markTranscriptReady(businessCallId, transcriptId));
    }

    @Override
    public void onTranscriptSegment(String tenantId, String businessCallId, Long transcriptId) {
        TenantHelper.dynamic(tenantId, () -> markRealtimeDirty(businessCallId, transcriptId));
    }

    @Override
    public void onTransferToAgent(String tenantId, String businessCallId, Long transcriptId) {
        TenantHelper.dynamic(tenantId, () -> markTransferToAgent(businessCallId, transcriptId));
    }

    @Transactional(rollbackFor = Exception.class)
    protected void markCallCompleted(AiTicketCallCompletedContext context) {
        AiRealtimeCallSession session = findAiSession(context.businessCallId());
        AiTicketPolicy policy = enabledPolicy(session);
        if (policy == null || !supportsTrigger(policy, "CALL_ENDED")) return;
        AiCallTranscript transcript = successfulTranscript(context.businessCallId());
        Map<String, Object> callContext = new LinkedHashMap<>();
        callContext.put("startedAt", context.startedAt());
        callContext.put("answeredAt", context.answeredAt());
        callContext.put("endedAt", context.endedAt());
        callContext.put("durationSeconds", context.durationSeconds());
        callContext.put("billableSeconds", context.billableSeconds());
        callContext.put("hangupCause", context.hangupCause());
        AiTicketDraftTask task = upsert(policy, session, context.callSessionId(), context.businessCallId());
        task.setTriggerType("CALL_ENDED");
        task.setCallCompleted(true);
        task.setNextRetryAt(null);
        task.setContextJson(JsonUtils.toJsonString(callContext));
        if (transcript != null) {
            task.setTranscriptReady(true);
            task.setTranscriptId(transcript.getId());
        }
        saveAndDispatch(task);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void markTranscriptReady(String businessCallId, Long transcriptId) {
        AiRealtimeCallSession session = findAiSession(businessCallId);
        AiTicketPolicy policy = enabledPolicy(session);
        if (policy == null || !supportsTrigger(policy, "CALL_ENDED")) return;
        AiCallRecordingSource source = callSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
            .eq(AiCallRecordingSource::getBusinessCallId, businessCallId).last("LIMIT 1"));
        AiTicketDraftTask task = upsert(policy, session, source == null ? null : source.getId(), businessCallId);
        task.setTranscriptReady(true);
        task.setTranscriptId(transcriptId);
        if (source != null && "ENDED".equals(source.getCallStatus())) task.setCallCompleted(true);
        saveAndDispatch(task);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void markRealtimeDirty(String businessCallId, Long transcriptId) {
        AiRealtimeCallSession session = findAiSession(businessCallId);
        AiTicketPolicy policy = enabledPolicy(session);
        if (policy == null || !supportsTrigger(policy, "TRANSFER_TO_AGENT")) return;
        AiCallRecordingSource source = callSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
            .eq(AiCallRecordingSource::getBusinessCallId, businessCallId).last("LIMIT 1"));
        AiTicketDraftTask task = upsert(policy, session, source == null ? null : source.getId(), businessCallId);
        task.setTriggerType("REALTIME_UPDATE");
        task.setTranscriptReady(true);
        task.setTranscriptId(transcriptId);
        task.setNextRetryAt(LocalDateTime.now().plusSeconds(REALTIME_DEBOUNCE_SECONDS));
        task.setFailureReason(null);
        task.setCompletedAt(null);
        if (!"PROCESSING".equals(task.getStatus())) task.setStatus("WAITING");
        taskMapper.updateById(task);
        dispatcher.dispatchAt(task.getId(), TenantHelper.getTenantId(), task.getNextRetryAt());
    }

    @Transactional(rollbackFor = Exception.class)
    protected void markTransferToAgent(String businessCallId, Long transcriptId) {
        AiRealtimeCallSession session = findAiSession(businessCallId);
        AiTicketPolicy policy = enabledPolicy(session);
        if (policy == null || !supportsTrigger(policy, "TRANSFER_TO_AGENT")) return;
        AiCallRecordingSource source = callSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
            .eq(AiCallRecordingSource::getBusinessCallId, businessCallId).last("LIMIT 1"));
        AiCallTranscript transcript = transcriptId == null ? successfulTranscript(businessCallId)
            : transcriptMapper.selectById(transcriptId);
        if (transcript == null) return;
        AiTicketDraftTask task = upsert(policy, session, source == null ? null : source.getId(), businessCallId);
        task.setTriggerType("TRANSFER_TO_AGENT");
        task.setTranscriptReady(true);
        task.setTranscriptId(transcript.getId());
        task.setNextRetryAt(LocalDateTime.now());
        task.setFailureReason(null);
        task.setCompletedAt(null);
        if (!"PROCESSING".equals(task.getStatus())) task.setStatus("READY");
        taskMapper.updateById(task);
        dispatcher.dispatchAfterCommit(task.getId(), TenantHelper.getTenantId());
    }

    private AiRealtimeCallSession findAiSession(String businessCallId) {
        return realtimeSessionMapper.selectOne(new LambdaQueryWrapper<AiRealtimeCallSession>()
            .eq(AiRealtimeCallSession::getBusinessCallId, businessCallId)
            .orderByDesc(AiRealtimeCallSession::getCreateTime).last("LIMIT 1"));
    }

    private AiTicketPolicy enabledPolicy(AiRealtimeCallSession session) {
        if (session == null || session.getAiAgentId() == null) return null;
        return policyMapper.selectOne(new LambdaQueryWrapper<AiTicketPolicy>()
            .eq(AiTicketPolicy::getAiAgentId, session.getAiAgentId())
            .eq(AiTicketPolicy::getEnabled, true).last("LIMIT 1"));
    }

    private AiCallTranscript successfulTranscript(String businessCallId) {
        return transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getBusinessCallId, businessCallId)
            .eq(AiCallTranscript::getStatus, "SUCCESS")
            .orderByDesc(AiCallTranscript::getFinishedAt).last("LIMIT 1"));
    }

    private boolean supportsTrigger(AiTicketPolicy policy, String trigger) {
        if (policy.getTriggerTypesJson() == null) return "CALL_ENDED".equals(trigger);
        try {
            List<?> values = JsonUtils.getObjectMapper().readValue(policy.getTriggerTypesJson(), List.class);
            return values.isEmpty() ? "CALL_ENDED".equals(trigger) : values.contains(trigger);
        } catch (Exception exception) {
            log.warn("AI 工单触发类型配置无效，policyId={}", policy.getId());
            return false;
        }
    }

    private AiTicketDraftTask upsert(AiTicketPolicy policy, AiRealtimeCallSession session,
                                     Long callSessionId, String businessCallId) {
        AiTicketDraftTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTicketDraftTask>()
            .eq(AiTicketDraftTask::getPolicyId, policy.getId())
            .eq(AiTicketDraftTask::getBusinessCallId, businessCallId).last("LIMIT 1"));
        if (task != null) return task;
        task = new AiTicketDraftTask();
        task.setPolicyId(policy.getId());
        task.setAiAgentId(session.getAiAgentId());
        task.setCallSessionId(callSessionId);
        task.setBusinessCallId(businessCallId);
        task.setTriggerType("CALL_ENDED");
        task.setCallCompleted(false);
        task.setTranscriptReady(false);
        task.setPromptVersionId(policy.getActivePromptVersionId());
        task.setStatus("WAITING");
        task.setRetryCount(0);
        task.setVersion(0);
        try {
            taskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException exception) {
            return taskMapper.selectOne(new LambdaQueryWrapper<AiTicketDraftTask>()
                .eq(AiTicketDraftTask::getPolicyId, policy.getId())
                .eq(AiTicketDraftTask::getBusinessCallId, businessCallId).last("LIMIT 1"));
        }
    }

    private void saveAndDispatch(AiTicketDraftTask task) {
        boolean ready = Boolean.TRUE.equals(task.getCallCompleted()) && Boolean.TRUE.equals(task.getTranscriptReady());
        if (ready && !List.of("SUCCESS", "SKIPPED", "FAILED", "PROCESSING").contains(task.getStatus())) {
            task.setStatus("READY");
        }
        taskMapper.updateById(task);
        if ("READY".equals(task.getStatus())) dispatcher.dispatchAfterCommit(task.getId(), TenantHelper.getTenantId());
    }
}
