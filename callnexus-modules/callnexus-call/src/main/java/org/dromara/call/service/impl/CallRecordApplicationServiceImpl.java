package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.CallRecord;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.request.CallRecordPageQuery;
import org.dromara.call.domain.response.CallRecordResponse;
import org.dromara.call.mapper.CallRecordMapper;
import org.dromara.call.service.CallRecordApplicationService;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class CallRecordApplicationServiceImpl implements CallRecordApplicationService {
    private final CallRecordMapper mapper;
    private final AgentRealtimeQueryService agentQueryService;
    private final FreeSwitchNodeQueryService nodeQueryService;

    @Override
    public void handleEvent(TelephonyEvent event) {
        if (event.uuid() == null || event.uuid().isBlank()) return;
        String tenantId = resolveTenantId(event);
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("跳过无法识别租户的通话事件，nodeId={}，eventName={}，uuid={}", event.nodeId(), event.eventName(), event.uuid());
            return;
        }
        TenantHelper.dynamic(tenantId, () -> upsert(event, tenantId));
    }

    @Override
    public TableDataInfo<CallRecordResponse> page(CallRecordPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<CallRecord> wrapper = new LambdaQueryWrapper<CallRecord>()
            .and(StringUtils.isNotBlank(query.getParticipantNumber()), condition -> condition
                .eq(CallRecord::getCallerNumber, query.getParticipantNumber())
                .or()
                .eq(CallRecord::getCalledNumber, query.getParticipantNumber()))
            .like(StringUtils.isNotBlank(query.getCallerNumber()), CallRecord::getCallerNumber, query.getCallerNumber())
            .like(StringUtils.isNotBlank(query.getCalledNumber()), CallRecord::getCalledNumber, query.getCalledNumber())
            .eq(StringUtils.isNotBlank(query.getDirection()), CallRecord::getDirection, query.getDirection())
            .eq(StringUtils.isNotBlank(query.getCallStatus()), CallRecord::getCallStatus, query.getCallStatus())
            .eq(StringUtils.isNotBlank(query.getHangupCause()), CallRecord::getHangupCause, query.getHangupCause())
            .orderByDesc(CallRecord::getStartedAt);
        Page<CallRecord> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public CallRecordResponse get(Long id) {
        CallRecord record = mapper.selectById(id);
        if (record == null) throw new ServiceException("CALL_RECORD_NOT_FOUND");
        return toResponse(record);
    }

    private void upsert(TelephonyEvent event, String tenantId) {
        CallRecord record = mapper.selectOne(new LambdaQueryWrapper<CallRecord>()
            .eq(CallRecord::getChannelUuid, event.uuid())
            .last("limit 1"));
        if (record == null) {
            record = newRecord(event, tenantId);
            try {
                mapper.insert(record);
                return;
            } catch (DuplicateKeyException ignored) {
                record = mapper.selectOne(new LambdaQueryWrapper<CallRecord>()
                    .eq(CallRecord::getChannelUuid, event.uuid())
                    .last("limit 1"));
            }
        }
        applyEvent(record, event, LocalDateTime.now());
        mapper.updateById(record);
    }

    private CallRecord newRecord(TelephonyEvent event, String tenantId) {
        CallRecord record = new CallRecord();
        record.setTenantId(tenantId);
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
        applyEvent(record, event, LocalDateTime.now());
        return record;
    }

    private void applyEvent(CallRecord record, TelephonyEvent event, LocalDateTime occurredAt) {
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
            case EslEventNames.CHANNEL_HANGUP_COMPLETE -> {
                record.setCallStatus("ENDED");
                record.setEndedAt(occurredAt);
                record.setHangupCause(event.hangupCause());
                record.setDurationSeconds(secondsBetween(record.getStartedAt(), occurredAt));
                record.setBillableSeconds(secondsBetween(record.getAnsweredAt(), occurredAt));
            }
            default -> {
            }
        }
    }

    private String resolveTenantId(TelephonyEvent event) {
        AgentRealtimeTargetResponse callingAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.callerNumber());
        if (callingAgent != null) return callingAgent.getTenantId();
        AgentRealtimeTargetResponse calledAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.destinationNumber());
        if (calledAgent != null) return calledAgent.getTenantId();
        return nodeQueryService.findTenantId(event.nodeId());
    }

    private String resolveDirection(TelephonyEvent event) {
        boolean callerIsAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.callerNumber()) != null;
        boolean calledIsAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.destinationNumber()) != null;
        if (callerIsAgent && calledIsAgent) return "INTERNAL";
        if (callerIsAgent) return "OUTBOUND";
        if (calledIsAgent) return "INBOUND";
        return "UNKNOWN";
    }

    private void applyAgent(CallRecord record, TelephonyEvent event) {
        AgentRealtimeTargetResponse agent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.destinationNumber());
        if (agent == null) agent = agentQueryService.findByNodeAndExtension(event.nodeId(), event.callerNumber());
        if (agent == null) return;
        record.setAgentId(agent.getAgentId());
        record.setAgentExtension(agent.getExtension());
    }

    private String resolveCallUuid(TelephonyEvent event) {
        return event.headers().entrySet().stream()
            .filter(entry -> EslHeaders.CHANNEL_CALL_UUID.equals(entry.getKey())
                || EslHeaders.VARIABLE_ORIGINATION_UUID.equals(entry.getKey())
                || EslHeaders.VARIABLE_BRIDGE_UUID.equals(entry.getKey()))
            .map(java.util.Map.Entry::getValue)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse(event.uuid());
    }

    private int secondsBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) return 0;
        return Math.toIntExact(Duration.between(start, end).toSeconds());
    }

    private CallRecordResponse toResponse(CallRecord record) {
        CallRecordResponse response = new CallRecordResponse();
        response.setId(record.getId());
        response.setNodeId(record.getNodeId());
        response.setChannelUuid(record.getChannelUuid());
        response.setCallUuid(record.getCallUuid());
        response.setDirection(record.getDirection());
        response.setCallerNumber(record.getCallerNumber());
        response.setCalledNumber(record.getCalledNumber());
        response.setAgentId(record.getAgentId());
        response.setAgentExtension(record.getAgentExtension());
        response.setCallStatus(record.getCallStatus());
        response.setStartedAt(record.getStartedAt());
        response.setRingingAt(record.getRingingAt());
        response.setAnsweredAt(record.getAnsweredAt());
        response.setEndedAt(record.getEndedAt());
        response.setDurationSeconds(record.getDurationSeconds());
        response.setBillableSeconds(record.getBillableSeconds());
        response.setHangupCause(record.getHangupCause());
        return response;
    }
}
