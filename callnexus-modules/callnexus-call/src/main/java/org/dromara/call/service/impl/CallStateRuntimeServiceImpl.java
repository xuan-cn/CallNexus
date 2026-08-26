package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;
import org.dromara.agent.service.AgentRealtimeQueryService;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.AgentCallSession;
import org.dromara.call.domain.CallBridge;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.mapper.AgentCallSessionMapper;
import org.dromara.call.mapper.CallBridgeMapper;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.CallStateRuntimeService;
import org.dromara.call.service.SipBusinessIdentityResolver;
import org.dromara.call.service.TelephonyEndpointIdentityResolver;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class CallStateRuntimeServiceImpl implements CallStateRuntimeService {
    private final CallSessionMapper sessionMapper;
    private final CallLegMapper legMapper;
    private final CallBridgeMapper bridgeMapper;
    private final AgentCallSessionMapper agentCallSessionMapper;
    private final AgentRealtimeQueryService agentQueryService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final SipBusinessIdentityResolver sipBusinessIdentityResolver;
    private final TelephonyEndpointIdentityResolver endpointIdentityResolver;

    @Override
    public void handleEvent(TelephonyEvent event) {
        if (event == null || StringUtils.isBlank(event.uuid()) || StringUtils.isBlank(event.eventName())) {
            return;
        }
        if (EslEventNames.CUSTOM.equals(event.eventName())) {
            return;
        }
        String tenantId = resolveTenantId(event);
        if (StringUtils.isBlank(tenantId)) {
            log.warn("跳过无法识别租户的通话状态事件，nodeId={}，eventName={}，uuid={}", event.nodeId(), event.eventName(), event.uuid());
            return;
        }
        TenantHelper.dynamic(tenantId, () -> persistEvent(event, tenantId));
    }

    @Override
    public String resolveBusinessCallId(TelephonyEvent event) {
        return resolveBusinessCallId(event, null);
    }

    @Override
    public String resolveBusinessCallId(TelephonyEvent event, AgentActiveCall existing) {
        if (event == null) {
            return existing == null ? null : existing.getBusinessCallId();
        }
        return firstNotBlank(
            event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_BUSINESS_CALL_ID),
            existing == null ? null : existing.getBusinessCallId(),
            event.headers().get(EslHeaders.VARIABLE_CC_MEMBER_SESSION_UUID),
            event.headers().get(EslHeaders.CC_CALLER_UUID),
            event.headers().get(EslHeaders.CC_MEMBER_UUID),
            existing == null ? null : existing.getCallId(),
            event.uuid()
        );
    }

    @Override
    public String resolveCanonicalBusinessCallId(TelephonyEvent event) {
        if (event == null) {
            return null;
        }
        return resolvePersistenceBusinessCallId(event, findLeg(event.uuid()));
    }

    private void persistEvent(TelephonyEvent event, String tenantId) {
        LocalDateTime now = TelephonyEventTimeResolver.resolve(event);
        CallLeg existingLeg = findLeg(event.uuid());
        String businessCallId = resolvePersistenceBusinessCallId(event, existingLeg);
        CallSession session = resolveSession(event, tenantId, businessCallId, existingLeg, now);
        CallLeg leg = upsertLeg(event, tenantId, session, businessCallId, existingLeg, now);
        updateBridge(event, tenantId, session, businessCallId, now);
        updateAgentCallSession(event, tenantId, session, leg, now);
        updateSessionOwner(event, session, leg);
        finalizeSessionIfNecessary(event, session, now);
    }

    private String resolvePersistenceBusinessCallId(TelephonyEvent event, CallLeg existingLeg) {
        String explicitBusinessCallId = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_BUSINESS_CALL_ID);
        if (existingLeg != null && StringUtils.isNotBlank(existingLeg.getBusinessCallId())) {
            if (StringUtils.isNotBlank(explicitBusinessCallId)
                && !existingLeg.getBusinessCallId().equals(explicitBusinessCallId)) {
                log.warn("通话事件业务通话ID与已落库电话腿不一致，保留电话腿原归属，eventName={}，uuid={}，persistedBusinessCallId={}，eventBusinessCallId={}",
                    event.eventName(), event.uuid(), existingLeg.getBusinessCallId(), explicitBusinessCallId);
            }
            return existingLeg.getBusinessCallId();
        }
        if (StringUtils.isNotBlank(explicitBusinessCallId)) {
            return explicitBusinessCallId;
        }
        List<String> relatedLegUuids = relatedUuids(event).stream()
            .filter(uuid -> !uuid.equals(event.uuid()))
            .toList();
        if (!relatedLegUuids.isEmpty()) {
            List<CallLeg> relatedLegs = legMapper.selectList(new LambdaQueryWrapper<CallLeg>()
                .in(CallLeg::getLegUuid, relatedLegUuids));
            CallLeg activeRelatedLeg = relatedLegs.stream()
                .filter(leg -> Boolean.TRUE.equals(leg.getActive()) && StringUtils.isNotBlank(leg.getBusinessCallId()))
                .findFirst()
                .orElse(null);
            if (activeRelatedLeg != null) {
                return activeRelatedLeg.getBusinessCallId();
            }
            CallLeg relatedLeg = relatedLegs.stream()
                .filter(leg -> StringUtils.isNotBlank(leg.getBusinessCallId()))
                .findFirst()
                .orElse(null);
            if (relatedLeg != null) {
                return relatedLeg.getBusinessCallId();
            }
        }
        return resolveBusinessCallId(event);
    }

    private CallSession resolveSession(TelephonyEvent event, String tenantId, String businessCallId,
                                       CallLeg existingLeg, LocalDateTime now) {
        if (existingLeg != null && existingLeg.getSessionId() != null) {
            CallSession persistedSession = sessionMapper.selectById(existingLeg.getSessionId());
            if (persistedSession != null) {
                return persistedSession;
            }
        }
        CallSession session = sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .last("limit 1"));
        if (session != null) {
            return session;
        }
        session = new CallSession();
        session.setTenantId(tenantId);
        session.setBusinessCallId(businessCallId);
        session.setNodeId(event.nodeId());
        session.setDirection(resolveDirection(event));
        session.setCallerNumber(businessNumber(event, originalCaller(event)));
        session.setCalledNumber(businessNumber(event, originalCalled(event)));
        session.setCallStatus("CREATED");
        session.setStartedAt(now);
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

    private CallLeg upsertLeg(TelephonyEvent event, String tenantId, CallSession session, String businessCallId,
                              CallLeg existingLeg, LocalDateTime now) {
        CallLeg leg = existingLeg;
        if (leg == null) {
            leg = new CallLeg();
            leg.setTenantId(tenantId);
            leg.setSessionId(session.getId());
            leg.setBusinessCallId(businessCallId);
            leg.setNodeId(event.nodeId());
            leg.setLegUuid(event.uuid());
            leg.setEndpointExtension(resolveEndpointExtension(event));
            applyAgent(leg, event);
            leg.setLegRole(resolveLegRole(event, leg));
            leg.setCallerNumber(businessNumber(event, event.callerNumber()));
            leg.setCalledNumber(businessNumber(event, event.destinationNumber()));
            leg.setLegState("CREATED");
            leg.setActive(true);
            applyLegEvent(leg, event, now);
            try {
                legMapper.insert(leg);
                return leg;
            } catch (DuplicateKeyException ignored) {
                leg = legMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
                    .eq(CallLeg::getLegUuid, event.uuid())
                    .last("limit 1"));
            }
        }
        if (leg == null) {
            throw new IllegalStateException("通话腿状态写入失败");
        }
        if (leg.getSessionId() == null) {
            leg.setSessionId(session.getId());
        } else if (!leg.getSessionId().equals(session.getId())) {
            log.warn("拒绝迁移已落库电话腿的业务会话归属，eventName={}，uuid={}，persistedSessionId={}，resolvedSessionId={}",
                event.eventName(), event.uuid(), leg.getSessionId(), session.getId());
        }
        if (StringUtils.isBlank(leg.getBusinessCallId())) {
            leg.setBusinessCallId(businessCallId);
        }
        if (StringUtils.isBlank(leg.getEndpointExtension())) {
            leg.setEndpointExtension(resolveEndpointExtension(event));
        }
        if (leg.getAgentId() == null) {
            applyAgent(leg, event);
        }
        if (StringUtils.isBlank(leg.getLegRole()) || "UNKNOWN".equals(leg.getLegRole())) {
            leg.setLegRole(resolveLegRole(event, leg));
        }
        if (StringUtils.isBlank(leg.getCallerNumber())) {
            leg.setCallerNumber(businessNumber(event, event.callerNumber()));
        }
        if (StringUtils.isBlank(leg.getCalledNumber())) {
            leg.setCalledNumber(businessNumber(event, event.destinationNumber()));
        }
        applyLegEvent(leg, event, now);
        legMapper.updateById(leg);
        return leg;
    }

    private CallLeg findLeg(String legUuid) {
        if (StringUtils.isBlank(legUuid)) {
            return null;
        }
        return legMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
    }

    private void applyLegEvent(CallLeg leg, TelephonyEvent event, LocalDateTime now) {
        switch (event.eventName()) {
            case EslEventNames.CHANNEL_PROGRESS, EslEventNames.CHANNEL_PROGRESS_MEDIA -> {
                boolean originatingEndpoint = StringUtils.isNotBlank(leg.getEndpointExtension())
                    && "inbound".equalsIgnoreCase(event.headers().get(EslHeaders.CALL_DIRECTION));
                leg.setLegState(originatingEndpoint ? "DIALING" : "RINGING");
                if (!originatingEndpoint && leg.getRingingAt() == null) {
                    leg.setRingingAt(now);
                }
            }
            case EslEventNames.CHANNEL_ANSWER -> {
                leg.setLegState("ANSWERED");
                if (leg.getAnsweredAt() == null) {
                    leg.setAnsweredAt(now);
                }
            }
            case EslEventNames.CHANNEL_BRIDGE -> {
                leg.setLegState("BRIDGED");
                if (leg.getBridgedAt() == null) {
                    leg.setBridgedAt(now);
                }
            }
            case EslEventNames.CHANNEL_HOLD -> {
                leg.setLegState("HELD");
                leg.setHeldAt(now);
            }
            case EslEventNames.CHANNEL_UNHOLD -> leg.setLegState(leg.getBridgedAt() == null ? "ANSWERED" : "BRIDGED");
            case EslEventNames.CHANNEL_HANGUP, EslEventNames.CHANNEL_HANGUP_COMPLETE, EslEventNames.CHANNEL_DESTROY -> {
                leg.setLegState("ENDED");
                leg.setActive(false);
                leg.setEndedAt(CallHangupCauseResolver.preserveFirst(leg.getEndedAt(), now));
                leg.setHangupCause(CallHangupCauseResolver.preserveFirst(leg.getHangupCause(), event.hangupCause()));
            }
            default -> {
            }
        }
    }

    private void updateBridge(TelephonyEvent event, String tenantId, CallSession session, String businessCallId, LocalDateTime now) {
        String peerUuid = firstRelatedUuid(event);
        if (StringUtils.isBlank(peerUuid)) {
            return;
        }
        if (EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
            markLegBridged(event.uuid(), now);
            markLegBridged(peerUuid, now);
            if (activeBridgeExists(event.uuid(), peerUuid)) {
                return;
            }
            CallBridge bridge = new CallBridge();
            bridge.setTenantId(tenantId);
            bridge.setSessionId(session.getId());
            bridge.setBusinessCallId(businessCallId);
            bridge.setNodeId(event.nodeId());
            bridge.setLeftLegUuid(event.uuid());
            bridge.setRightLegUuid(peerUuid);
            bridge.setBridgeType(resolveBridgeType(event, event.uuid(), peerUuid));
            bridge.setBridgeState("BRIDGED");
            bridge.setStartedAt(now);
            bridgeMapper.insert(bridge);
            return;
        }
        if (EslEventNames.CHANNEL_UNBRIDGE.equals(event.eventName())
            || EslEventNames.CHANNEL_HANGUP.equals(event.eventName())
            || EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())) {
            bridgeMapper.update(null, new LambdaUpdateWrapper<CallBridge>()
                .eq(CallBridge::getBridgeState, "BRIDGED")
                .and(wrapper -> wrapper
                    .nested(pair -> pair.eq(CallBridge::getLeftLegUuid, event.uuid()).eq(CallBridge::getRightLegUuid, peerUuid))
                    .or(pair -> pair.eq(CallBridge::getLeftLegUuid, peerUuid).eq(CallBridge::getRightLegUuid, event.uuid())))
                .set(CallBridge::getBridgeState, "UNBRIDGED")
                .set(CallBridge::getEndedAt, now));
        }
    }

    private void markLegBridged(String legUuid, LocalDateTime now) {
        if (StringUtils.isBlank(legUuid)) {
            return;
        }
        CallLeg leg = legMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
        if (leg == null || Boolean.FALSE.equals(leg.getActive())) {
            return;
        }
        leg.setLegState("BRIDGED");
        if (leg.getBridgedAt() == null) {
            leg.setBridgedAt(now);
        }
        legMapper.updateById(leg);
    }

    private boolean activeBridgeExists(String left, String right) {
        return bridgeMapper.exists(new LambdaQueryWrapper<CallBridge>()
            .eq(CallBridge::getBridgeState, "BRIDGED")
            .and(wrapper -> wrapper
                .nested(pair -> pair.eq(CallBridge::getLeftLegUuid, left).eq(CallBridge::getRightLegUuid, right))
                .or(pair -> pair.eq(CallBridge::getLeftLegUuid, right).eq(CallBridge::getRightLegUuid, left))));
    }

    private void updateAgentCallSession(TelephonyEvent event, String tenantId, CallSession session, CallLeg leg, LocalDateTime now) {
        if (leg.getAgentId() == null || StringUtils.isBlank(leg.getAgentExtension())) {
            return;
        }
        AgentCallSession agentSession = agentCallSessionMapper.selectOne(new LambdaQueryWrapper<AgentCallSession>()
            .eq(AgentCallSession::getAgentLegUuid, leg.getLegUuid())
            .last("limit 1"));
        if (agentSession == null) {
            agentSession = new AgentCallSession();
            agentSession.setTenantId(tenantId);
            agentSession.setSessionId(session.getId());
            agentSession.setBusinessCallId(session.getBusinessCallId());
            agentSession.setNodeId(event.nodeId());
            agentSession.setAgentId(leg.getAgentId());
            agentSession.setAgentExtension(leg.getAgentExtension());
            agentSession.setAgentLegUuid(leg.getLegUuid());
            agentSession.setRole(resolveAgentCallRole(event, leg));
            boolean supervision = isSupervisionRole(leg.getLegRole());
            agentSession.setSessionState(supervision ? supervisionState(leg.getLegRole()) : "ACTIVE");
            agentSession.setVisible(!supervision);
            agentSession.setJoinedAt(now);
            try {
                agentCallSessionMapper.insert(agentSession);
                return;
            } catch (DuplicateKeyException ignored) {
                agentSession = agentCallSessionMapper.selectOne(new LambdaQueryWrapper<AgentCallSession>()
                    .eq(AgentCallSession::getAgentLegUuid, leg.getLegUuid())
                    .last("limit 1"));
            }
        }
        if (agentSession == null) {
            return;
        }
        agentSession.setSessionId(session.getId());
        agentSession.setBusinessCallId(session.getBusinessCallId());
        if (EslEventNames.CHANNEL_HANGUP.equals(event.eventName())
            || EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())
            || EslEventNames.CHANNEL_DESTROY.equals(event.eventName())) {
            agentSession.setSessionState("ENDED");
            agentSession.setVisible(false);
            if (agentSession.getLeftAt() == null) {
                agentSession.setLeftAt(now);
            }
        } else if (EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
            boolean supervision = isSupervisionRole(leg.getLegRole());
            agentSession.setSessionState(supervision ? supervisionState(leg.getLegRole()) : isConsultEvent(event) ? "CONSULTING" : "ACTIVE");
            agentSession.setVisible(!supervision);
        }
        agentCallSessionMapper.updateById(agentSession);
    }

    private void updateSessionOwner(TelephonyEvent event, CallSession session, CallLeg leg) {
        if (leg.getAgentId() == null || !Boolean.TRUE.equals(leg.getActive())
            || isSupervisionRole(leg.getLegRole()) || isDispatchCallRole(leg.getLegRole())) {
            return;
        }
        if (!EslEventNames.CHANNEL_ANSWER.equals(event.eventName()) && !EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
            return;
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<CallSession>()
            .eq(CallSession::getId, session.getId())
            .set(CallSession::getOwnerAgentId, leg.getAgentId())
            .set(CallSession::getOwnerAgentExtension, leg.getAgentExtension())
            .set(CallSession::getOwnerAgentLegUuid, leg.getLegUuid())
            .set(CallSession::getCurrentBridgeState, EslEventNames.CHANNEL_BRIDGE.equals(event.eventName()) ? "BRIDGED" : "ANSWERED"));
    }

    private void finalizeSessionIfNecessary(TelephonyEvent event, CallSession session, LocalDateTime now) {
        if (!EslEventNames.CHANNEL_HANGUP.equals(event.eventName())
            && !EslEventNames.CHANNEL_HANGUP_COMPLETE.equals(event.eventName())
            && !EslEventNames.CHANNEL_DESTROY.equals(event.eventName())) {
            return;
        }
        boolean hasActiveLeg = legMapper.exists(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getSessionId, session.getId())
            .eq(CallLeg::getActive, true));
        if (hasActiveLeg) {
            return;
        }
        bridgeMapper.update(null, new LambdaUpdateWrapper<CallBridge>()
            .eq(CallBridge::getSessionId, session.getId())
            .eq(CallBridge::getBridgeState, "BRIDGED")
            .set(CallBridge::getBridgeState, "UNBRIDGED")
            .set(CallBridge::getEndedAt, now));
        agentCallSessionMapper.update(null, new LambdaUpdateWrapper<AgentCallSession>()
            .eq(AgentCallSession::getSessionId, session.getId())
            .ne(AgentCallSession::getSessionState, "ENDED")
            .set(AgentCallSession::getSessionState, "ENDED")
            .set(AgentCallSession::getVisible, false)
            .set(AgentCallSession::getLeftAt, now));

        LambdaUpdateWrapper<CallSession> update = new LambdaUpdateWrapper<CallSession>()
            .eq(CallSession::getId, session.getId())
            .set(CallSession::getCallStatus, "ENDED")
            .set(CallSession::getEndedAt, now)
            .set(CallSession::getDurationSeconds, secondsBetween(session.getStartedAt(), now))
            .set(CallSession::getBillableSeconds, secondsBetween(session.getAnsweredAt(), now))
            .set(CallSession::getCurrentBridgeState, "UNBRIDGED")
            .set(CallSession::getOwnerAgentId, null)
            .set(CallSession::getOwnerAgentExtension, null)
            .set(CallSession::getOwnerAgentLegUuid, null);
        List<CallLeg> sessionLegs = legMapper.selectList(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getSessionId, session.getId()));
        String resolvedHangupCause = CallHangupCauseResolver.resolveSessionCause(
            sessionLegs, session.getHangupCause(), event.hangupCause());
        if (StringUtils.isNotBlank(resolvedHangupCause)) {
            update.set(CallSession::getHangupCause, resolvedHangupCause);
        }
        sessionMapper.update(null, update);
        log.info("稳定通话状态已在最后活动电话腿结束后收口，sessionId={}，businessCallId={}，lastLegUuid={}，cause={}",
            session.getId(), session.getBusinessCallId(), event.uuid(), resolvedHangupCause);
    }

    private int secondsBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        return (int) Duration.between(start, end).getSeconds();
    }

    private String resolveTenantId(TelephonyEvent event) {
        String callerIdentity = firstNotBlank(
            endpointIdentityResolver.resolveKnownExtension(event.nodeId(), originalCaller(event)),
            event.callerNumber());
        AgentRealtimeTargetResponse callingAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), callerIdentity);
        if (callingAgent != null) {
            return callingAgent.getTenantId();
        }
        String calledIdentity = firstNotBlank(
            endpointIdentityResolver.resolveKnownExtension(event.nodeId(), originalCalled(event)),
            event.destinationNumber());
        AgentRealtimeTargetResponse calledAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), calledIdentity);
        if (calledAgent != null) {
            return calledAgent.getTenantId();
        }
        String tenantId = event.headers().get("tenantId");
        if (StringUtils.isNotBlank(tenantId)) {
            return tenantId;
        }
        return nodeQueryService.findTenantId(event.nodeId());
    }

    private void applyAgent(CallLeg leg, TelephonyEvent event) {
        AgentRealtimeTargetResponse agent = resolveAgent(event, leg.getEndpointExtension());
        if (agent == null) {
            return;
        }
        leg.setAgentId(agent.getAgentId());
        leg.setAgentExtension(agent.getExtension());
    }

    private AgentRealtimeTargetResponse resolveAgent(TelephonyEvent event, String endpointExtension) {
        String consultLegUuid = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CONSULT_LEG_UUID);
        String sourceLegUuid = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_SOURCE_AGENT_LEG_UUID);
        if (event.uuid().equals(consultLegUuid)) {
            return findAgentByExtension(event, event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_TARGET_AGENT_EXTENSION));
        }
        if (event.uuid().equals(sourceLegUuid)) {
            return findAgentByExtension(event, event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_SOURCE_AGENT_EXTENSION));
        }
        return findAgentByExtension(event, endpointExtension);
    }

    private String resolveEndpointExtension(TelephonyEvent event) {
        return endpointIdentityResolver.resolveChannelExtension(event);
    }

    private String extensionFromChannelName(String channelName) {
        if (StringUtils.isBlank(channelName)) {
            return null;
        }
        String lowerChannelName = channelName.toLowerCase();
        String endpoint;
        if (lowerChannelName.startsWith("sofia/internal/")) {
            endpoint = channelName.substring("sofia/internal/".length());
        } else if (lowerChannelName.startsWith("user/")) {
            endpoint = channelName.substring("user/".length());
        } else {
            return null;
        }
        return stripDomainIdentity(endpoint);
    }

    private String enabledSipExtension(Long nodeId, String value) {
        return sipBusinessIdentityResolver.resolveExtension(nodeId, value);
    }

    private String businessNumber(TelephonyEvent event, String value) {
        return sipBusinessIdentityResolver.resolveBusinessNumber(event.nodeId(), value);
    }

    private AgentRealtimeTargetResponse findAgentByExtension(TelephonyEvent event, String value) {
        String identity = firstNotBlank(
            endpointIdentityResolver.resolveKnownExtension(event.nodeId(), value),
            stripDomainIdentity(value));
        return identity == null ? null : agentQueryService.findByNodeAndExtension(event.nodeId(), identity);
    }

    private String resolveLegRole(TelephonyEvent event, CallLeg leg) {
        if (isDispatchCallEvent(event)) {
            return dispatchCallRole(event);
        }
        if (isDispatchPickupEvent(event)) {
            return "PICKUP";
        }
        if (isDispatchSupervisionEvent(event)) {
            return supervisionRole(event);
        }
        if (isConsultEvent(event) && event.uuid().equals(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CONSULT_LEG_UUID))) {
            return "CONSULT_AGENT";
        }
        if (leg.getAgentId() != null) {
            return "AGENT";
        }
        if (StringUtils.isNotBlank(leg.getEndpointExtension())) {
            return "EXTENSION";
        }
        return "CUSTOMER";
    }

    private String resolveAgentCallRole(TelephonyEvent event, CallLeg leg) {
        if (isDispatchCallRole(leg.getLegRole())) {
            return leg.getLegRole();
        }
        if ("PICKUP".equals(leg.getLegRole())) {
            return "PICKUP";
        }
        if (isSupervisionRole(leg.getLegRole())) {
            return leg.getLegRole();
        }
        if ("CONSULT_AGENT".equals(leg.getLegRole())) {
            return "CONSULT_TARGET";
        }
        if (isConsultEvent(event) && event.uuid().equals(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_SOURCE_AGENT_LEG_UUID))) {
            return "SOURCE";
        }
        return "OWNER";
    }

    private String resolveBridgeType(TelephonyEvent event, String leftLegUuid, String rightLegUuid) {
        if (isDispatchPickupEvent(event)) {
            return "PICKUP";
        }
        if (isDispatchSupervisionEvent(event)) {
            return supervisionRole(event);
        }
        String leftRole = legRole(leftLegUuid);
        String rightRole = legRole(rightLegUuid);
        if (isSupervisionRole(leftRole)) {
            return leftRole;
        }
        if (isSupervisionRole(rightRole)) {
            return rightRole;
        }
        if (("CUSTOMER".equals(leftRole) && "PICKUP".equals(rightRole))
            || ("PICKUP".equals(leftRole) && "CUSTOMER".equals(rightRole))) {
            return "PICKUP";
        }
        if (isConsultEvent(event)) {
            return "CONSULT";
        }
        if (isConsultBridge(leftRole, rightRole)) {
            return "CONSULT";
        }
        if (isTransferBridge(leftRole, rightRole)) {
            return "TRANSFER";
        }
        if (StringUtils.isNotBlank(event.headers().get(EslHeaders.CC_QUEUE))) {
            return "QUEUE";
        }
        return "NORMAL";
    }

    private String legRole(String legUuid) {
        if (StringUtils.isBlank(legUuid)) {
            return null;
        }
        CallLeg leg = legMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .select(CallLeg::getLegRole)
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
        return leg == null ? null : leg.getLegRole();
    }

    private boolean isConsultBridge(String leftRole, String rightRole) {
        return ("AGENT".equals(leftRole) && "CONSULT_AGENT".equals(rightRole))
            || ("CONSULT_AGENT".equals(leftRole) && "AGENT".equals(rightRole));
    }

    private boolean isTransferBridge(String leftRole, String rightRole) {
        return ("CUSTOMER".equals(leftRole) && "CONSULT_AGENT".equals(rightRole))
            || ("CONSULT_AGENT".equals(leftRole) && "CUSTOMER".equals(rightRole));
    }

    private boolean isConsultEvent(TelephonyEvent event) {
        return "CONSULT".equalsIgnoreCase(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE));
    }

    private boolean isDispatchSupervisionEvent(TelephonyEvent event) {
        String purpose = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE);
        return "DISPATCH_MONITOR".equalsIgnoreCase(purpose)
            || "DISPATCH_WHISPER".equalsIgnoreCase(purpose)
            || "DISPATCH_BARGE".equalsIgnoreCase(purpose);
    }

    private boolean isDispatchPickupEvent(TelephonyEvent event) {
        return "DISPATCH_PICKUP".equalsIgnoreCase(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE));
    }

    private boolean isDispatchCallEvent(TelephonyEvent event) {
        String purpose = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE);
        return "DISPATCH_CALL_OPERATOR".equalsIgnoreCase(purpose)
            || "DISPATCH_CALL_TARGET".equalsIgnoreCase(purpose)
            || "DISPATCH_INTERCOM_OPERATOR".equalsIgnoreCase(purpose)
            || "DISPATCH_INTERCOM_TARGET".equalsIgnoreCase(purpose)
            || "DISPATCH_BROADCAST_TARGET".equalsIgnoreCase(purpose);
    }

    private String dispatchCallRole(TelephonyEvent event) {
        String purpose = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE);
        return ("DISPATCH_CALL_OPERATOR".equalsIgnoreCase(purpose)
            || "DISPATCH_INTERCOM_OPERATOR".equalsIgnoreCase(purpose))
            ? "DISPATCH_OPERATOR"
            : "DISPATCH_TARGET";
    }

    private boolean isDispatchCallRole(String role) {
        return "DISPATCH_OPERATOR".equals(role) || "DISPATCH_TARGET".equals(role);
    }

    private String supervisionRole(TelephonyEvent event) {
        String purpose = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CALL_PURPOSE);
        if ("DISPATCH_WHISPER".equalsIgnoreCase(purpose)) return "WHISPER";
        if ("DISPATCH_BARGE".equalsIgnoreCase(purpose)) return "BARGE";
        return "MONITOR";
    }

    private boolean isSupervisionRole(String role) {
        return "MONITOR".equals(role) || "WHISPER".equals(role) || "BARGE".equals(role);
    }

    private String supervisionState(String role) {
        if ("WHISPER".equals(role)) return "WHISPERING";
        if ("BARGE".equals(role)) return "BARGING";
        return "MONITORING";
    }

    private String resolveDirection(TelephonyEvent event) {
        String explicitDirection = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_DIRECTION);
        if (StringUtils.isNotBlank(explicitDirection)) {
            return explicitDirection;
        }
        String caller = firstNotBlank(
            endpointIdentityResolver.resolveKnownExtension(event.nodeId(), originalCaller(event)),
            event.callerNumber());
        String called = firstNotBlank(
            endpointIdentityResolver.resolveKnownExtension(event.nodeId(), originalCalled(event)),
            event.destinationNumber());
        boolean callerIsAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), caller) != null;
        boolean calledIsAgent = agentQueryService.findByNodeAndExtension(event.nodeId(), called) != null;
        if (callerIsAgent && calledIsAgent) {
            return "INTERNAL";
        }
        if (callerIsAgent) {
            return "OUTBOUND";
        }
        if (calledIsAgent) {
            return "INBOUND";
        }
        return "UNKNOWN";
    }

    private String originalCaller(TelephonyEvent event) {
        String caller = firstNotBlank(
            event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CUSTOMER_PHONE),
            event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLER));
        return StringUtils.isNotBlank(caller) ? caller : event.callerNumber();
    }

    private String originalCalled(TelephonyEvent event) {
        String called = event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_ORIGINAL_CALLED);
        return StringUtils.isNotBlank(called) ? called : event.destinationNumber();
    }

    private String firstRelatedUuid(TelephonyEvent event) {
        return relatedUuids(event).stream()
            .filter(uuid -> !uuid.equals(event.uuid()))
            .findFirst()
            .orElse(null);
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
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CUSTOMER_LEG_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_SOURCE_AGENT_LEG_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_CONSULT_LEG_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.VARIABLE_CC_MEMBER_SESSION_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.CC_CALLER_UUID));
        addUuid(uuids, event.headers().get(EslHeaders.CC_MEMBER_UUID));
        return uuids;
    }

    private void addUuid(Set<String> uuids, String uuid) {
        if (StringUtils.isNotBlank(uuid)) {
            uuids.add(uuid);
        }
    }

    private String normalizeExtension(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("user/")) {
            normalized = normalized.substring("user/".length());
        }
        int atIndex = normalized.indexOf('@');
        if (atIndex > 0) {
            normalized = normalized.substring(0, atIndex);
        }
        normalized = normalized.replaceAll("[^0-9*#+]", "");
        return normalized.isBlank() ? null : normalized;
    }

    private String stripDomainIdentity(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String identity = value.trim();
        if (identity.startsWith("[")) {
            int close = identity.indexOf(']');
            if (close >= 0) identity = identity.substring(close + 1).trim();
        }
        if (identity.startsWith("user/")) {
            identity = identity.substring("user/".length());
        }
        int atIndex = identity.indexOf('@');
        if (atIndex > 0) {
            identity = identity.substring(0, atIndex);
        }
        return identity.isBlank() ? null : identity;
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
