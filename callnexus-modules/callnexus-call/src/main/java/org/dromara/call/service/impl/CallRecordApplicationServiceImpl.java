package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.CallEvent;
import org.dromara.call.domain.CallRecord;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.request.CallRecordPageQuery;
import org.dromara.call.domain.response.CallEventResponse;
import org.dromara.call.domain.response.CallLegResponse;
import org.dromara.call.domain.response.CallRecordResponse;
import org.dromara.call.mapper.CallEventMapper;
import org.dromara.call.mapper.CallRecordMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.CallRecordApplicationService;
import org.dromara.call.service.BusinessAssociationQueryService;
import org.dromara.call.service.QueueEventApplicationService;
import org.dromara.call.service.CallBusinessAssociationService;
import org.dromara.common.core.service.OssService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class CallRecordApplicationServiceImpl implements CallRecordApplicationService, CallBusinessAssociationService {
    private final CallRecordMapper recordMapper;
    private final CallSessionMapper sessionMapper;
    private final CallEventMapper eventMapper;
    private final AgentRealtimeQueryService agentQueryService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final OssService ossService;
    private final QueueEventApplicationService queueEventApplicationService;
    private final BusinessAssociationQueryService businessAssociationQueryService;

    /**
     * 通话录音回放预签名链接默认有效期：2 小时。
     * 录音对象通常存放在私有桶中，前端 audio 标签需要带签名才能直接播放，
     * 因此在通话详情查询时按对象 key 实时签发临时直链。
     */
    private static final Duration RECORDING_PRESIGNED_TTL = Duration.ofHours(2);

    @Override
    public void handleEvent(TelephonyEvent event) {
        if (StringUtils.isBlank(event.uuid())) return;
        String tenantId = resolveTenantId(event);
        if (StringUtils.isBlank(tenantId)) {
            log.warn("跳过无法识别租户的通话事件，nodeId={}，eventName={}，uuid={}", event.nodeId(), event.eventName(), event.uuid());
            return;
        }
        TenantHelper.dynamic(tenantId, () -> persistEvent(event, tenantId));
    }

    @Override
    public TableDataInfo<CallRecordResponse> page(CallRecordPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<CallSession> wrapper = new LambdaQueryWrapper<CallSession>()
            .eq(query.getCustomerId() != null, CallSession::getCustomerId, query.getCustomerId())
            .eq(query.getTicketId() != null, CallSession::getTicketId, query.getTicketId())
            .and(StringUtils.isNotBlank(query.getParticipantNumber()), condition -> condition
                .eq(CallSession::getCallerNumber, query.getParticipantNumber())
                .or()
                .eq(CallSession::getCalledNumber, query.getParticipantNumber()))
            .like(StringUtils.isNotBlank(query.getCallerNumber()), CallSession::getCallerNumber, query.getCallerNumber())
            .like(StringUtils.isNotBlank(query.getCalledNumber()), CallSession::getCalledNumber, query.getCalledNumber())
            .eq(StringUtils.isNotBlank(query.getDirection()), CallSession::getDirection, query.getDirection())
            .eq(StringUtils.isNotBlank(query.getCallStatus()), CallSession::getCallStatus, query.getCallStatus())
            .eq(StringUtils.isNotBlank(query.getHangupCause()), CallSession::getHangupCause, query.getHangupCause())
            .orderByDesc(CallSession::getStartedAt);
        Page<CallSession> page = sessionMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(session -> toResponse(session, false)).toList(), page.getTotal());
    }

    @Override
    public CallRecordResponse get(Long id) {
        CallSession session = sessionMapper.selectById(id);
        if (session == null) throw new ServiceException("通话记录不存在");
        return toResponse(session, true);
    }

    @Override
    public void associateCustomer(String businessCallId, Long customerId) {
        if (StringUtils.isBlank(businessCallId) || customerId == null) return;
        sessionMapper.update(null, new LambdaUpdateWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .set(CallSession::getCustomerId, customerId));
    }

    @Override
    public void associateTicket(String businessCallId, Long ticketId, Long customerId) {
        if (StringUtils.isBlank(businessCallId) || ticketId == null) return;
        LambdaUpdateWrapper<CallSession> update = new LambdaUpdateWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .set(CallSession::getTicketId, ticketId);
        if (customerId != null) update.set(CallSession::getCustomerId, customerId);
        sessionMapper.update(null, update);
    }

    private void persistEvent(TelephonyEvent event, String tenantId) {
        CallSession session = resolveSession(event, tenantId);
        applySessionMetadata(session, event);
        CallRecord record = upsertLeg(event, tenantId, session.getId());
        appendTimelineEvent(session.getId(), event);
        aggregateSession(session, record);
    }

    private CallSession resolveSession(TelephonyEvent event, String tenantId) {
        Set<String> relatedUuids = relatedUuids(event);
        String explicitBusinessCallId = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_BUSINESS_CALL_ID);
        List<CallSession> candidates = StringUtils.isNotBlank(explicitBusinessCallId)
            ? sessionMapper.selectList(new LambdaQueryWrapper<CallSession>()
                .eq(CallSession::getBusinessCallId, explicitBusinessCallId))
            : List.of();

        if (!relatedUuids.isEmpty()) {
            List<Long> relatedSessionIds = recordMapper.selectList(new LambdaQueryWrapper<CallRecord>()
                    .in(CallRecord::getChannelUuid, relatedUuids)
                    .or()
                    .in(CallRecord::getCallUuid, relatedUuids))
                .stream()
                .map(CallRecord::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            if (!relatedSessionIds.isEmpty()) {
                candidates = new java.util.ArrayList<>(candidates);
                candidates.addAll(sessionMapper.selectBatchIds(relatedSessionIds));
            }
        }

        List<CallSession> distinctCandidates = candidates.stream()
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toMap(CallSession::getId, value -> value, (left, right) -> left))
            .values().stream()
            .sorted(Comparator.comparing(CallSession::getId))
            .toList();
        if (distinctCandidates.isEmpty()) {
            return createSession(event, tenantId, StringUtils.isNotBlank(explicitBusinessCallId) ? explicitBusinessCallId : event.uuid());
        }

        CallSession primary = distinctCandidates.get(0);
        for (int index = 1; index < distinctCandidates.size(); index++) {
            mergeSession(primary, distinctCandidates.get(index));
        }
        if (StringUtils.isNotBlank(explicitBusinessCallId) && !explicitBusinessCallId.equals(primary.getBusinessCallId())) {
            primary.setBusinessCallId(explicitBusinessCallId);
            sessionMapper.updateById(primary);
        }
        return primary;
    }

    private CallSession createSession(TelephonyEvent event, String tenantId, String businessCallId) {
        CallSession session = new CallSession();
        session.setTenantId(tenantId);
        session.setBusinessCallId(businessCallId);
        session.setNodeId(event.nodeId());
        session.setDirection(resolveDirection(event));
        session.setCallerNumber(originalCaller(event));
        session.setCalledNumber(originalCalled(event));
        applyAgent(session, event);
        session.setCallStatus("CREATED");
        session.setStartedAt(LocalDateTime.now());
        session.setDurationSeconds(0);
        session.setBillableSeconds(0);
        try {
            sessionMapper.insert(session);
            return session;
        } catch (DuplicateKeyException ignored) {
            return sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
                .eq(CallSession::getBusinessCallId, businessCallId)
                .last("limit 1"));
        }
    }

    private void mergeSession(CallSession primary, CallSession duplicate) {
        if (primary.getId().equals(duplicate.getId())) return;
        recordMapper.update(null, new LambdaUpdateWrapper<CallRecord>()
            .eq(CallRecord::getSessionId, duplicate.getId())
            .set(CallRecord::getSessionId, primary.getId()));
        eventMapper.update(null, new LambdaUpdateWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, duplicate.getId())
            .set(CallEvent::getSessionId, primary.getId()));
        sessionMapper.deleteById(duplicate.getId());
        log.info("合并同一业务通话的临时会话，primarySessionId={}，mergedSessionId={}", primary.getId(), duplicate.getId());
    }

    private CallRecord upsertLeg(TelephonyEvent event, String tenantId, Long sessionId) {
        CallRecord record = recordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
            .eq(CallRecord::getChannelUuid, event.uuid())
            .last("limit 1"));
        if (record == null) {
            record = newLeg(event, tenantId, sessionId);
            try {
                recordMapper.insert(record);
                return record;
            } catch (DuplicateKeyException ignored) {
                record = recordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
                    .eq(CallRecord::getChannelUuid, event.uuid())
                    .last("limit 1"));
            }
        }
        record.setSessionId(sessionId);
        applyLegEvent(record, event, LocalDateTime.now());
        recordMapper.updateById(record);
        return record;
    }

    private CallRecord newLeg(TelephonyEvent event, String tenantId, Long sessionId) {
        CallRecord record = new CallRecord();
        record.setTenantId(tenantId);
        record.setSessionId(sessionId);
        record.setNodeId(event.nodeId());
        record.setChannelUuid(event.uuid());
        record.setCallUuid(resolveCallUuid(event));
        record.setCallerNumber(event.callerNumber());
        record.setCalledNumber(event.destinationNumber());
        record.setDirection(resolveDirection(event));
        applyAgent(record, event);
        record.setCallStatus("CREATED");
        record.setStartedAt(LocalDateTime.now());
        record.setDurationSeconds(0);
        record.setBillableSeconds(0);
        applyLegEvent(record, event, LocalDateTime.now());
        return record;
    }

    private void applyLegEvent(CallRecord record, TelephonyEvent event, LocalDateTime occurredAt) {
        if (StringUtils.isBlank(record.getCallerNumber())) record.setCallerNumber(event.callerNumber());
        if (StringUtils.isBlank(record.getCalledNumber())) record.setCalledNumber(event.destinationNumber());
        if (StringUtils.isBlank(record.getCallUuid())) record.setCallUuid(resolveCallUuid(event));
        if (record.getAgentId() == null) applyAgent(record, event);
        switch (event.eventName()) {
            case EslEventNames.CHANNEL_PROGRESS, EslEventNames.CHANNEL_PROGRESS_MEDIA -> {
                record.setCallStatus("RINGING");
                if (record.getRingingAt() == null) record.setRingingAt(occurredAt);
            }
            case EslEventNames.CHANNEL_ANSWER -> {
                record.setCallStatus("ANSWERED");
                if (record.getAnsweredAt() == null) record.setAnsweredAt(occurredAt);
            }
            case EslEventNames.CHANNEL_BRIDGE -> record.setCallStatus("BRIDGED");
            case EslEventNames.CHANNEL_HANGUP, EslEventNames.CHANNEL_HANGUP_COMPLETE, EslEventNames.CHANNEL_DESTROY -> {
                record.setCallStatus("ENDED");
                if (record.getEndedAt() == null || occurredAt.isAfter(record.getEndedAt())) record.setEndedAt(occurredAt);
                if (StringUtils.isNotBlank(event.hangupCause())) record.setHangupCause(event.hangupCause());
                record.setDurationSeconds(secondsBetween(record.getStartedAt(), occurredAt));
                record.setBillableSeconds(secondsBetween(record.getAnsweredAt(), occurredAt));
            }
            default -> {
            }
        }
    }

    private void appendTimelineEvent(Long sessionId, TelephonyEvent event) {
        String eventType = timelineEventType(event.eventName());
        if (eventType == null) return;
        String relatedChannelUuid = firstRelatedChannelUuid(event);
        if (EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
            if (bridgePairExists(sessionId, event.uuid(), relatedChannelUuid)) return;
            boolean hasPreviousBridge = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
                .eq(CallEvent::getSessionId, sessionId)
                .in(CallEvent::getEventType, "BRIDGED", "TRANSFERRED"));
            if (hasPreviousBridge) eventType = "TRANSFERRED";
        }
        if (isSingleOccurrenceTimelineEvent(eventType)) {
            boolean exists = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
                .eq(CallEvent::getSessionId, sessionId)
                .eq(CallEvent::getChannelUuid, event.uuid())
                .eq(CallEvent::getEventType, eventType));
            if (exists) return;
        }
        CallEvent timelineEvent = new CallEvent();
        timelineEvent.setSessionId(sessionId);
        timelineEvent.setChannelUuid(event.uuid());
        timelineEvent.setRelatedChannelUuid(relatedChannelUuid);
        timelineEvent.setEventType(eventType);
        timelineEvent.setFromTarget(event.callerNumber());
        timelineEvent.setToTarget(event.destinationNumber());
        timelineEvent.setOccurredAt(LocalDateTime.now());
        eventMapper.insert(timelineEvent);
    }

    private boolean bridgePairExists(Long sessionId, String channelUuid, String relatedChannelUuid) {
        if (StringUtils.isBlank(relatedChannelUuid)) return false;
        return eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .in(CallEvent::getEventType, "BRIDGED", "TRANSFERRED")
            .and(condition -> condition
                .nested(pair -> pair.eq(CallEvent::getChannelUuid, channelUuid)
                    .eq(CallEvent::getRelatedChannelUuid, relatedChannelUuid))
                .or(pair -> pair.eq(CallEvent::getChannelUuid, relatedChannelUuid)
                    .eq(CallEvent::getRelatedChannelUuid, channelUuid))));
    }

    private void aggregateSession(CallSession session, CallRecord currentRecord) {
        List<CallRecord> legs = recordMapper.selectList(new LambdaQueryWrapper<CallRecord>()
            .eq(CallRecord::getSessionId, session.getId())
            .orderByAsc(CallRecord::getStartedAt));
        if (legs.isEmpty()) return;
        session.setStartedAt(earliest(legs.stream().map(CallRecord::getStartedAt).toList()));
        session.setRingingAt(earliest(legs.stream().map(CallRecord::getRingingAt).toList()));
        session.setAnsweredAt(earliest(legs.stream().map(CallRecord::getAnsweredAt).toList()));
        session.setEndedAt(legs.stream().allMatch(leg -> "ENDED".equals(leg.getCallStatus()))
            ? latest(legs.stream().map(CallRecord::getEndedAt).toList()) : null);
        session.setCallStatus(aggregateStatus(legs));
        session.setDirection(aggregateDirection(legs));
        CallRecord agentLeg = legs.stream().filter(leg -> leg.getAgentId() != null).reduce((left, right) -> right).orElse(null);
        if (agentLeg != null) {
            session.setAgentId(agentLeg.getAgentId());
            session.setAgentExtension(agentLeg.getAgentExtension());
        }
        if (session.getEndedAt() != null) {
            session.setDurationSeconds(secondsBetween(session.getStartedAt(), session.getEndedAt()));
            session.setBillableSeconds(secondsBetween(session.getAnsweredAt(), session.getEndedAt()));
            String hangupCause = legs.stream()
                .filter(leg -> StringUtils.isNotBlank(leg.getHangupCause()))
                .reduce((left, right) -> right)
                .map(CallRecord::getHangupCause)
                .orElse(currentRecord.getHangupCause());
            session.setHangupCause(hangupCause);
        }
        sessionMapper.updateById(session);
        // 业务通话聚合结束：若为队列来电且未被接听，按挂断原因推断队列超时或主叫放弃节点。
        if ("ENDED".equals(session.getCallStatus())) {
            try {
                queueEventApplicationService.recordQueueTerminationIfUnanswered(
                    session.getId(), session.getBusinessCallId(), session.getHangupCause());
            } catch (Exception exception) {
                log.warn("记录队列未接听终止事件失败，不影响通话聚合，sessionId={}", session.getId(), exception);
            }
        }
    }

    private String aggregateStatus(List<CallRecord> legs) {
        if (legs.stream().allMatch(leg -> "ENDED".equals(leg.getCallStatus()))) return "ENDED";
        if (legs.stream().anyMatch(leg -> "BRIDGED".equals(leg.getCallStatus()))) return "BRIDGED";
        if (legs.stream().anyMatch(leg -> "ANSWERED".equals(leg.getCallStatus()))) return "ANSWERED";
        if (legs.stream().anyMatch(leg -> "RINGING".equals(leg.getCallStatus()))) return "RINGING";
        return "CREATED";
    }

    private String aggregateDirection(List<CallRecord> legs) {
        if (legs.stream().anyMatch(leg -> "INBOUND".equals(leg.getDirection()))) return "INBOUND";
        if (legs.stream().anyMatch(leg -> "OUTBOUND".equals(leg.getDirection()))) return "OUTBOUND";
        if (legs.stream().anyMatch(leg -> "INTERNAL".equals(leg.getDirection()))) return "INTERNAL";
        return "UNKNOWN";
    }

    private String timelineEventType(String eventName) {
        return switch (eventName) {
            case EslEventNames.CHANNEL_CREATE -> "CALL_LEG_CREATED";
            case EslEventNames.CHANNEL_PROGRESS, EslEventNames.CHANNEL_PROGRESS_MEDIA -> "RINGING";
            case EslEventNames.CHANNEL_ANSWER -> "ANSWERED";
            case EslEventNames.CHANNEL_BRIDGE -> "BRIDGED";
            case EslEventNames.CHANNEL_UNBRIDGE -> "UNBRIDGED";
            case EslEventNames.CHANNEL_HOLD -> "HELD";
            case EslEventNames.CHANNEL_UNHOLD -> "UNHELD";
            case EslEventNames.CHANNEL_HANGUP, EslEventNames.CHANNEL_HANGUP_COMPLETE, EslEventNames.CHANNEL_DESTROY -> "CALL_LEG_ENDED";
            default -> null;
        };
    }

    private boolean isSingleOccurrenceTimelineEvent(String eventType) {
        return "CALL_LEG_CREATED".equals(eventType)
            || "RINGING".equals(eventType)
            || "ANSWERED".equals(eventType)
            || "CALL_LEG_ENDED".equals(eventType);
    }

    private Set<String> relatedUuids(TelephonyEvent event) {
        Set<String> uuids = new LinkedHashSet<>();
        addUuid(uuids, event.uuid());
        addUuid(uuids, event.headers().get(EslHeaders.OTHER_LEG_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.BRIDGE_A_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.BRIDGE_B_UNIQUE_ID));
        addUuid(uuids, event.headers().get(EslHeaders.CHANNEL_CALL_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_ORIGINATION_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_BRIDGE_UUID));
        return uuids;
    }

    private String firstRelatedChannelUuid(TelephonyEvent event) {
        return relatedUuids(event).stream().filter(uuid -> !uuid.equals(event.uuid())).findFirst().orElse(null);
    }

    private void addUuid(Set<String> uuids, String uuid) {
        if (StringUtils.isNotBlank(uuid)) uuids.add(uuid);
    }

    private String resolveTenantId(TelephonyEvent event) {
        AgentRealtimeTargetResponse callingAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.callerNumber());
        if (callingAgent != null) return callingAgent.getTenantId();
        AgentRealtimeTargetResponse calledAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.destinationNumber());
        if (calledAgent != null) return calledAgent.getTenantId();
        return nodeQueryService.findTenantId(event.nodeId());
    }

    private String resolveDirection(TelephonyEvent event) {
        String explicitDirection = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_DIRECTION);
        if (StringUtils.isNotBlank(explicitDirection)) return explicitDirection;
        boolean callerIsAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.callerNumber()) != null;
        boolean calledIsAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.destinationNumber()) != null;
        if (callerIsAgent && calledIsAgent) return "INTERNAL";
        if (callerIsAgent) return "OUTBOUND";
        if (calledIsAgent) return "INBOUND";
        return "UNKNOWN";
    }

    private void applySessionMetadata(CallSession session, TelephonyEvent event) {
        boolean changed = false;
        String direction = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_DIRECTION);
        String caller = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLER);
        String called = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLED);
        Long customerId = parseLong(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CUSTOMER_ID));
        Long outboundTaskId = parseLong(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_OUTBOUND_TASK_ID));
        Long outboundMemberId = parseLong(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_OUTBOUND_MEMBER_ID));
        if (StringUtils.isNotBlank(direction) && !direction.equals(session.getDirection())) {
            session.setDirection(direction);
            changed = true;
        }
        if (StringUtils.isNotBlank(caller) && !caller.equals(session.getCallerNumber())) {
            session.setCallerNumber(caller);
            changed = true;
        }
        if (StringUtils.isNotBlank(called) && !called.equals(session.getCalledNumber())) {
            session.setCalledNumber(called);
            changed = true;
        }
        if (customerId != null && !customerId.equals(session.getCustomerId())) {
            session.setCustomerId(customerId);
            changed = true;
        }
        if (outboundTaskId != null && !outboundTaskId.equals(session.getOutboundTaskId())) {
            session.setOutboundTaskId(outboundTaskId);
            changed = true;
        }
        if (outboundMemberId != null && !outboundMemberId.equals(session.getOutboundMemberId())) {
            session.setOutboundMemberId(outboundMemberId);
            changed = true;
        }
        if (changed) sessionMapper.updateById(session);
    }

    private Long parseLong(String value) {
        if (StringUtils.isBlank(value) || !value.matches("^\\d+$")) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String originalCaller(TelephonyEvent event) {
        String caller = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLER);
        return StringUtils.isNotBlank(caller) ? caller : event.callerNumber();
    }

    private String originalCalled(TelephonyEvent event) {
        String called = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLED);
        return StringUtils.isNotBlank(called) ? called : event.destinationNumber();
    }

    private void applyAgent(CallRecord record, TelephonyEvent event) {
        AgentRealtimeTargetResponse agent = findAgent(event);
        if (agent == null) return;
        record.setAgentId(agent.getAgentId());
        record.setAgentExtension(agent.getExtension());
    }

    private void applyAgent(CallSession session, TelephonyEvent event) {
        AgentRealtimeTargetResponse agent = findAgent(event);
        if (agent == null) return;
        session.setAgentId(agent.getAgentId());
        session.setAgentExtension(agent.getExtension());
    }

    private AgentRealtimeTargetResponse findAgent(TelephonyEvent event) {
        AgentRealtimeTargetResponse agent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.destinationNumber());
        return agent == null ? agentQueryService.findByNodeAndExtension(event.nodeId(), event.callerNumber()) : agent;
    }

    private String resolveCallUuid(TelephonyEvent event) {
        String callUuid = event.headers().get(EslHeaders.CHANNEL_CALL_UUID);
        if (StringUtils.isBlank(callUuid)) callUuid = event.headers().get(EslHeaders.VARIABLE_ORIGINATION_UUID);
        if (StringUtils.isBlank(callUuid)) callUuid = event.uuid();
        return callUuid;
    }

    private LocalDateTime earliest(List<LocalDateTime> values) {
        return values.stream().filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
    }

    private LocalDateTime latest(List<LocalDateTime> values) {
        return values.stream().filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
    }

    private int secondsBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) return 0;
        return Math.toIntExact(Duration.between(start, end).toSeconds());
    }

    private CallRecordResponse toResponse(CallSession session, boolean includeDetails) {
        CallRecordResponse response = new CallRecordResponse();
        response.setId(session.getId());
        response.setBusinessCallId(session.getBusinessCallId());
        response.setNodeId(session.getNodeId());
        response.setCallUuid(session.getBusinessCallId());
        response.setDirection(session.getDirection());
        response.setCallerNumber(session.getCallerNumber());
        response.setCalledNumber(session.getCalledNumber());
        response.setAgentId(session.getAgentId());
        response.setAgentExtension(session.getAgentExtension());
        response.setHandlingQueueId(session.getHandlingQueueId());
        response.setHandlingQueueName(session.getHandlingQueueName());
        response.setCustomerId(session.getCustomerId());
        response.setTicketId(session.getTicketId());
        response.setOutboundTaskId(session.getOutboundTaskId());
        response.setOutboundMemberId(session.getOutboundMemberId());
        // 详情查询时，显式关联为空则按号码回查历史客户/工单，避免队列来电未创建客户工单时详情空白。
        // 列表查询不做回查，避免 N+1 查询。
        if (includeDetails) {
            applyAssociationFallback(response, session);
        }
        response.setCallStatus(session.getCallStatus());
        response.setStartedAt(session.getStartedAt());
        response.setRingingAt(session.getRingingAt());
        response.setAnsweredAt(session.getAnsweredAt());
        response.setEndedAt(session.getEndedAt());
        response.setDurationSeconds(session.getDurationSeconds());
        response.setBillableSeconds(session.getBillableSeconds());
        response.setHangupCause(session.getHangupCause());
        response.setRecordingOssId(session.getRecordingOssId());
        response.setRecordingMediaId(session.getRecordingMediaId());
        response.setRecordingFileName(session.getRecordingFileName());
        response.setRecordingStatus(session.getRecordingStatus());
        if (session.getRecordingOssId() != null) {
            response.setRecordingUrl(buildRecordingPlaybackUrl(session.getRecordingOssId()));
        }
        if (includeDetails) {
            response.setLegs(recordMapper.selectList(new LambdaQueryWrapper<CallRecord>()
                    .eq(CallRecord::getSessionId, session.getId())
                    .orderByAsc(CallRecord::getStartedAt))
                .stream().map(this::toLegResponse).toList());
            response.setEvents(eventMapper.selectList(new LambdaQueryWrapper<CallEvent>()
                    .eq(CallEvent::getSessionId, session.getId())
                    .orderByAsc(CallEvent::getOccurredAt))
                .stream().map(this::toEventResponse).toList());
        }
        return response;
    }

    /**
     * 详情查询时，显式关联为空则按号码回查历史客户和工单。
     *
     * <p>优先用主叫号码查客户（队列来电的主叫通常是客户号码）；
     * 工单按主叫号码匹配工单的 callerNumber 字段，取最新一条。
     * 回查失败不影响详情展示，仅记录 WARN。
     */
    private void applyAssociationFallback(CallRecordResponse response, CallSession session) {
        if (response.getCustomerId() == null && StringUtils.isNotBlank(session.getCallerNumber())) {
            try {
                Long customerId = businessAssociationQueryService.findCustomerIdByPhone(session.getCallerNumber());
                if (customerId != null) response.setCustomerId(customerId);
            } catch (Exception exception) {
                log.warn("按号码回查历史客户失败，不影响详情展示，callerNumber={}", session.getCallerNumber(), exception);
            }
        }
        if (response.getTicketId() == null && StringUtils.isNotBlank(session.getCallerNumber())) {
            try {
                Long ticketId = businessAssociationQueryService.findLatestTicketIdByCallerNumber(session.getCallerNumber());
                if (ticketId != null) response.setTicketId(ticketId);
            } catch (Exception exception) {
                log.warn("按号码回查历史工单失败，不影响详情展示，callerNumber={}", session.getCallerNumber(), exception);
            }
        }
    }

    private CallLegResponse toLegResponse(CallRecord record) {
        CallLegResponse response = new CallLegResponse();
        response.setId(record.getId());
        response.setChannelUuid(record.getChannelUuid());
        response.setCallUuid(record.getCallUuid());
        response.setDirection(record.getDirection());
        response.setCallerNumber(record.getCallerNumber());
        response.setCalledNumber(record.getCalledNumber());
        response.setAgentExtension(record.getAgentExtension());
        response.setCallStatus(record.getCallStatus());
        response.setStartedAt(record.getStartedAt());
        response.setAnsweredAt(record.getAnsweredAt());
        response.setEndedAt(record.getEndedAt());
        response.setDurationSeconds(record.getDurationSeconds());
        response.setBillableSeconds(record.getBillableSeconds());
        response.setHangupCause(record.getHangupCause());
        return response;
    }

    private String buildRecordingPlaybackUrl(Long ossId) {
        return ossService.selectUrlById(ossId, RECORDING_PRESIGNED_TTL);
    }

    private CallEventResponse toEventResponse(CallEvent event) {
        CallEventResponse response = new CallEventResponse();
        response.setId(event.getId());
        response.setChannelUuid(event.getChannelUuid());
        response.setRelatedChannelUuid(event.getRelatedChannelUuid());
        response.setEventType(event.getEventType());
        response.setFromTarget(event.getFromTarget());
        response.setToTarget(event.getToTarget());
        response.setOccurredAt(event.getOccurredAt());
        return response;
    }
}
