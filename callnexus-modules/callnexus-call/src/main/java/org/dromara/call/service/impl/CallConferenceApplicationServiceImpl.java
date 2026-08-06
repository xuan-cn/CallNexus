package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.agent.domain.AgentConsultCall;
import org.dromara.agent.domain.AgentConsultCallStatus;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.AgentSessionApplicationService;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.call.domain.CallConference;
import org.dromara.call.domain.CallConferenceMember;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.event.CallLifecycleEvent;
import org.dromara.call.domain.response.CallConferenceMemberResponse;
import org.dromara.call.domain.response.CallConferenceResponse;
import org.dromara.call.mapper.CallConferenceMapper;
import org.dromara.call.mapper.CallConferenceMemberMapper;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.CallConferenceApplicationService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class CallConferenceApplicationServiceImpl implements CallConferenceApplicationService {
    private static final String ACTIVE_CALL_KEY_PREFIX = "callnexus:agent:active-call:";
    private static final String CONSULT_CALL_KEY_PREFIX = "callnexus:agent:consult-call:";
    private static final String CONSULT_LEG_KEY_PREFIX = "callnexus:call:consult-leg:";
    private static final Duration ACTIVE_CALL_TTL = Duration.ofHours(4);

    private final CurrentAgentSessionService agentSessionService;
    private final AgentSessionApplicationService explicitAgentSessionService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway commandGateway;
    private final CallSessionMapper sessionMapper;
    private final CallLegMapper legMapper;
    private final CallConferenceMapper conferenceMapper;
    private final CallConferenceMemberMapper memberMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public CallConferenceResponse create(String callId) {
        return create(null, callId);
    }

    @Override
    public CallConferenceResponse create(Long agentId, String callId) {
        ConferenceContext context = requireConferenceContext(agentId, callId, true);
        CallConference existing = activeConference(context.businessCallId());
        if (existing != null) {
            return refresh(existing);
        }
        if (context.liveLegs().size() != 2) {
            throw new ServiceException("当前通话不是明确的双电话腿状态，无法升级为多方通话");
        }
        if (!commandGateway.callsAreBridged(context.endpoint(), context.ownerLeg().getLegUuid(),
            context.counterpartyLeg().getLegUuid())) {
            throw new ServiceException("当前电话腿未处于双人桥接状态，无法升级为多方通话");
        }

        LocalDateTime now = LocalDateTime.now();
        CallConference conference = new CallConference();
        conference.setSessionId(context.session().getId());
        conference.setBusinessCallId(context.businessCallId());
        conference.setNodeId(context.agent().getNodeId());
        conference.setConferenceName("call_" + context.businessCallId().replace("-", ""));
        conference.setDisplayName("多方会议");
        conference.setOwnerAgentId(context.agent().getAgentId());
        conference.setOwnerExtension(context.agent().getExtension());
        conference.setConferenceState("CREATING");
        conference.setStartedAt(now);
        conferenceMapper.insert(conference);

        CallConferenceMember owner = newMember(conference, context.ownerLeg(), "OWNER_AGENT",
            context.agent().getAgentId(), context.agent().getExtension(), context.agent().getAgentName(), now);
        CallConferenceMember counterparty = newMember(conference, context.counterpartyLeg(),
            "CUSTOMER".equals(context.counterpartyLeg().getLegRole()) ? "CUSTOMER" : "COUNTERPARTY",
            context.counterpartyLeg().getAgentId(), memberExtension(context.counterpartyLeg()),
            counterpartyName(context.counterpartyLeg(), context.session()), now);
        memberMapper.insert(owner);
        memberMapper.insert(counterparty);

        try {
            commandGateway.promoteBridgeToConference(context.endpoint(), context.ownerLeg().getLegUuid(),
                conference.getConferenceName());
            conference.setConferenceState("ACTIVE");
            conferenceMapper.updateById(conference);
            log.info("当前双人通话已升级为多方会议，tenantId={}，businessCallId={}，sessionId={}，nodeId={}，conferenceName={}，ownerLegUuid={}，counterpartyLegUuid={}",
                TenantHelper.getTenantId(), context.businessCallId(), context.session().getId(),
                context.agent().getNodeId(), conference.getConferenceName(),
                context.ownerLeg().getLegUuid(), context.counterpartyLeg().getLegUuid());
            publishConferenceEvent("conference.created", conference, null, Map.of("mode", "PROMOTED_CALL"));
            return refresh(conference);
        } catch (RuntimeException exception) {
            conference.setConferenceState("FAILED");
            conference.setFailureReason(exception.getMessage());
            conference.setEndedAt(LocalDateTime.now());
            conferenceMapper.updateById(conference);
            throw exception;
        }
    }

    @Override
    public CallConferenceResponse createStandalone(Long agentId, String displayName, List<String> targetExtensions) {
        CurrentAgentResponse agent = requireAgent(agentId);
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(agent.getAgentId()));
        if (activeCall != null) {
            throw new ServiceException("坐席当前存在活动通话，不能创建独立会议");
        }

        String businessCallId = UUID.randomUUID().toString();
        String normalizedDisplayName = displayName == null ? null : displayName.trim();
        if (normalizedDisplayName == null || normalizedDisplayName.isBlank()) {
            normalizedDisplayName = "会议-" + businessCallId.substring(0, 8);
        }
        LocalDateTime now = LocalDateTime.now();
        CallSession session = new CallSession();
        session.setBusinessCallId(businessCallId);
        session.setNodeId(agent.getNodeId());
        session.setDirection("INTERNAL");
        session.setCallerNumber(agent.getExtension());
        session.setCalledNumber("CONFERENCE");
        session.setAgentId(agent.getAgentId());
        session.setAgentExtension(agent.getExtension());
        session.setOwnerAgentId(agent.getAgentId());
        session.setOwnerAgentExtension(agent.getExtension());
        session.setCurrentBridgeState("UNBRIDGED");
        session.setCallStatus("CREATED");
        session.setStartedAt(now);
        session.setDurationSeconds(0);
        session.setBillableSeconds(0);
        sessionMapper.insert(session);

        CallConference conference = new CallConference();
        conference.setSessionId(session.getId());
        conference.setBusinessCallId(businessCallId);
        conference.setNodeId(agent.getNodeId());
        conference.setConferenceName("call_" + businessCallId.replace("-", ""));
        conference.setDisplayName(normalizedDisplayName);
        conference.setOwnerAgentId(agent.getAgentId());
        conference.setOwnerExtension(agent.getExtension());
        conference.setConferenceState("CREATING");
        conference.setStartedAt(now);
        conferenceMapper.insert(conference);

        EslEndpoint endpoint = endpoint(agent.getNodeId());
        CallConferenceMember owner = standaloneMember(conference, "OWNER_AGENT", agent.getAgentId(),
            agent.getExtension(), agent.getAgentName(), now);
        memberMapper.insert(owner);
        try {
            commandGateway.originateConferenceParticipant(endpoint, businessCallId, owner.getLegUuid(),
                conference.getConferenceName(), agent.getExtension(), agent.getExtension());
            markStandaloneSessionRinging(session, now);
            conference.setConferenceState("ACTIVE");
            conferenceMapper.updateById(conference);
        } catch (RuntimeException exception) {
            markStandaloneConferenceFailed(session, conference, owner, exception);
            throw exception;
        }

        for (String extension : normalizeExtensions(targetExtensions)) {
            if (agent.getExtension().equals(extension)) {
                continue;
            }
            try {
                inviteMember(agent, conference, endpoint, extension);
            } catch (RuntimeException exception) {
                log.warn("独立会议成员邀请失败，继续处理其他成员，conferenceId={}，businessCallId={}，targetExtension={}，reason={}",
                    conference.getId(), businessCallId, extension, exception.getMessage());
            }
        }
        log.info("独立会议创建成功，tenantId={}，conferenceId={}，businessCallId={}，conferenceName={}，ownerAgentId={}，ownerExtension={}，targetCount={}",
            TenantHelper.getTenantId(), conference.getId(), businessCallId, conference.getConferenceName(),
            agent.getAgentId(), agent.getExtension(), normalizeExtensions(targetExtensions).size());
        publishConferenceEvent("conference.created", conference, owner, Map.of("mode", "STANDALONE"));
        return refresh(conference);
    }

    @Override
    public CallConferenceResponse getById(Long agentId, Long conferenceId) {
        ConferenceManagementContext context = requireConferenceManagementContextById(agentId, conferenceId);
        return refresh(context.conference());
    }

    @Override
    public CallConferenceResponse inviteById(Long agentId, Long conferenceId, List<String> targetExtensions) {
        ConferenceManagementContext context = requireConferenceManagementContextById(agentId, conferenceId);
        for (String extension : normalizeExtensions(targetExtensions)) {
            inviteMember(context.agent(), context.conference(), context.endpoint(), extension);
        }
        return refresh(context.conference());
    }

    @Override
    public CallConferenceResponse muteMemberById(Long agentId, Long conferenceId, Long memberRecordId, boolean muted) {
        ConferenceManagementContext context = requireConferenceManagementContextById(agentId, conferenceId);
        return muteMember(context, memberRecordId, muted);
    }

    @Override
    public CallConferenceResponse removeMemberById(Long agentId, Long conferenceId, Long memberRecordId) {
        ConferenceManagementContext context = requireConferenceManagementContextById(agentId, conferenceId);
        return removeMember(context, memberRecordId);
    }

    @Override
    public void leaveById(Long agentId, Long conferenceId) {
        ConferenceManagementContext context = requireConferenceManagementContextById(agentId, conferenceId);
        leaveConference(context);
    }

    @Override
    public void endById(Long agentId, Long conferenceId) {
        ConferenceManagementContext context = requireConferenceManagementContextById(agentId, conferenceId);
        endConference(context);
    }

    @Override
    public CallConferenceResponse promoteConsult(String callId) {
        return promoteConsult(null, callId);
    }

    @Override
    public CallConferenceResponse promoteConsult(Long agentId, String callId) {
        ConferenceContext context = requireConferenceContext(agentId, callId, false);
        AgentConsultCall consultCall = RedisUtils.getCacheObject(consultCallKey(context.agent().getAgentId()));
        if (consultCall == null || !matchesConsult(consultCall, callId)) {
            throw new ServiceException("当前咨询通话不存在或已结束");
        }
        if (isConsultTransitionInProgressOrEnded(consultCall.getStatus())) {
            throw new ServiceException("当前咨询正在执行其他操作或已结束，无法转为三方通话");
        }
        String customerLegUuid = firstNotBlank(consultCall.getCustomerLegUuid(), consultCall.getCustomerCallId());
        String sourceLegUuid = firstNotBlank(consultCall.getSourceAgentLegUuid(), consultCall.getSourceAgentCallId());
        String targetLegUuid = firstNotBlank(consultCall.getConsultLegUuid(), consultCall.getTargetAgentCallId());
        requireLiveLeg(context.endpoint(), customerLegUuid, "客户腿");
        requireLiveLeg(context.endpoint(), sourceLegUuid, "源坐席腿");
        requireLiveLeg(context.endpoint(), targetLegUuid, "咨询坐席腿");
        if (!commandGateway.callsAreBridged(context.endpoint(), sourceLegUuid, targetLegUuid)) {
            throw new ServiceException("源坐席腿与咨询坐席腿尚未桥接，无法转为三方通话");
        }
        if (consultCall.getStatus() != AgentConsultCallStatus.CONSULT_TALKING
            && consultCall.getStatus() != AgentConsultCallStatus.CONNECTED) {
            AgentConsultCallStatus staleStatus = consultCall.getStatus();
            consultCall.setStatus(AgentConsultCallStatus.CONSULT_TALKING);
            if (consultCall.getConsultBridgedAt() == null) {
                consultCall.setConsultBridgedAt(LocalDateTime.now());
            }
            saveConsultState(context.agent(), consultCall);
            log.warn("咨询 Redis 状态滞后，已根据 FreeSWITCH 实际桥接关系校正，businessCallId={}，sourceAgentLegUuid={}，consultLegUuid={}，oldStatus={}，newStatus={}",
                context.businessCallId(), sourceLegUuid, targetLegUuid, staleStatus, consultCall.getStatus());
        }

        CallConference existing = activeConference(context.businessCallId());
        if (existing != null) {
            deleteConsultState(context.agent(), consultCall);
            return refresh(existing);
        }
        CallLeg customerLeg = requireCallLeg(context.businessCallId(), customerLegUuid, "客户腿");
        CallLeg sourceLeg = requireCallLeg(context.businessCallId(), sourceLegUuid, "源坐席腿");
        CallLeg targetLeg = requireCallLeg(context.businessCallId(), targetLegUuid, "咨询坐席腿");
        LocalDateTime now = LocalDateTime.now();
        CallConference conference = new CallConference();
        conference.setSessionId(context.session().getId());
        conference.setBusinessCallId(context.businessCallId());
        conference.setNodeId(context.agent().getNodeId());
        conference.setConferenceName("call_" + context.businessCallId().replace("-", ""));
        conference.setDisplayName("咨询三方会议");
        conference.setOwnerAgentId(context.agent().getAgentId());
        conference.setOwnerExtension(context.agent().getExtension());
        conference.setConferenceState("CREATING");
        conference.setStartedAt(now);
        conferenceMapper.insert(conference);

        memberMapper.insert(newMember(conference, sourceLeg, "OWNER_AGENT", context.agent().getAgentId(),
            context.agent().getExtension(), context.agent().getAgentName(), now));
        memberMapper.insert(newMember(conference, customerLeg, "CUSTOMER", customerLeg.getAgentId(),
            memberExtension(customerLeg), counterpartyName(customerLeg, context.session()), now));
        memberMapper.insert(newMember(conference, targetLeg, "CONSULT_AGENT", consultCall.getTargetAgentId(),
            consultCall.getTargetExtension(), "分机" + consultCall.getTargetExtension(), now));

        try {
            prepareConferenceLeg(context.endpoint(), customerLegUuid);
            prepareConferenceLeg(context.endpoint(), sourceLegUuid);
            prepareConferenceLeg(context.endpoint(), targetLegUuid);
            commandGateway.promoteBridgeToConference(context.endpoint(), sourceLegUuid, conference.getConferenceName());
            commandGateway.joinCallToConference(context.endpoint(), customerLegUuid, conference.getConferenceName());
            conference.setConferenceState("ACTIVE");
            conferenceMapper.updateById(conference);
            consultCall.setStatus(AgentConsultCallStatus.COMPLETED);
            consultCall.setCompletedAt(now);
            deleteConsultState(context.agent(), consultCall);
            log.info("咨询通话已升级为三方会议，tenantId={}，businessCallId={}，conferenceName={}，customerLegUuid={}，sourceAgentLegUuid={}，consultLegUuid={}",
                TenantHelper.getTenantId(), context.businessCallId(), conference.getConferenceName(), customerLegUuid,
                sourceLegUuid, targetLegUuid);
            publishConferenceEvent("conference.created", conference, null, Map.of("mode", "CONSULT_PROMOTION"));
            return refresh(conference);
        } catch (RuntimeException exception) {
            conference.setConferenceState("FAILED");
            conference.setFailureReason(exception.getMessage());
            conference.setEndedAt(LocalDateTime.now());
            conferenceMapper.updateById(conference);
            throw exception;
        }
    }

    @Override
    public CallConferenceResponse get(String callId) {
        return get(null, callId);
    }

    @Override
    public CallConferenceResponse get(Long agentId, String callId) {
        CurrentAgentResponse agent = requireAgent(agentId);
        CallConference conference = findActiveConferenceForManagement(agent, callId);
        if (conference == null) {
            return null;
        }
        assertOwner(conference, agent);
        return refresh(conference);
    }

    @Override
    public CallConferenceResponse invite(String callId, String targetExtension) {
        return invite(null, callId, targetExtension);
    }

    @Override
    public CallConferenceResponse invite(Long agentId, String callId, String targetExtension) {
        ConferenceManagementContext context = requireConferenceManagementContext(agentId, callId);
        inviteMember(context.agent(), context.conference(), context.endpoint(), targetExtension);
        return refresh(context.conference());
    }

    private CallConferenceMember inviteMember(CurrentAgentResponse agent, CallConference conference,
                                               EslEndpoint endpoint, String targetExtension) {
        String normalizedExtension = targetExtension == null ? null : targetExtension.trim();
        if (normalizedExtension == null || normalizedExtension.isBlank()) {
            throw new ServiceException("目标分机不能为空");
        }
        if (normalizedExtension.equals(agent.getExtension())) {
            throw new ServiceException("不能邀请当前坐席分机");
        }
        boolean alreadyActive = memberMapper.exists(new LambdaQueryWrapper<CallConferenceMember>()
            .eq(CallConferenceMember::getConferenceId, conference.getId())
            .eq(CallConferenceMember::getExtension, normalizedExtension)
            .in(CallConferenceMember::getMemberState, List.of("INVITING", "JOINED")));
        if (alreadyActive) {
            throw new ServiceException("该分机已在会议中或正在邀请");
        }
        String legUuid = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        CallConferenceMember member = new CallConferenceMember();
        member.setConferenceId(conference.getId());
        member.setSessionId(conference.getSessionId());
        member.setBusinessCallId(conference.getBusinessCallId());
        member.setLegUuid(legUuid);
        member.setMemberRole("INVITED_EXTENSION");
        member.setExtension(normalizedExtension);
        member.setDisplayName("分机" + normalizedExtension);
        member.setMemberState("INVITING");
        member.setMuted(false);
        member.setInvitedAt(now);
        memberMapper.insert(member);
        try {
            commandGateway.originateConferenceParticipant(endpoint, conference.getBusinessCallId(), legUuid,
                conference.getConferenceName(), normalizedExtension, agent.getExtension());
            log.info("多方会议邀请已提交，tenantId={}，businessCallId={}，conferenceId={}，conferenceName={}，targetExtension={}，participantLegUuid={}",
                TenantHelper.getTenantId(), conference.getBusinessCallId(), conference.getId(),
                conference.getConferenceName(), normalizedExtension, legUuid);
            publishConferenceEvent("conference.member_invited", conference, member, Map.of());
        } catch (RuntimeException exception) {
            member.setMemberState("FAILED");
            member.setFailureReason(exception.getMessage());
            member.setLeftAt(LocalDateTime.now());
            memberMapper.updateById(member);
            throw exception;
        }
        return member;
    }

    @Override
    public CallConferenceResponse muteMember(String callId, Long memberRecordId, boolean muted) {
        return muteMember(null, callId, memberRecordId, muted);
    }

    @Override
    public CallConferenceResponse muteMember(Long agentId, String callId, Long memberRecordId, boolean muted) {
        ConferenceManagementContext context = requireConferenceManagementContext(agentId, callId);
        return muteMember(context, memberRecordId, muted);
    }

    private CallConferenceResponse muteMember(ConferenceManagementContext context, Long memberRecordId, boolean muted) {
        CallConference conference = context.conference();
        CallConferenceMember member = requireMember(conference, memberRecordId);
        if ("OWNER_AGENT".equals(member.getMemberRole())) {
            throw new ServiceException("当前坐席请使用通话静音，不通过会议成员控制");
        }
        requireLiveMemberId(member);
        commandGateway.muteConferenceMember(context.endpoint(), conference.getConferenceName(),
            member.getConferenceMemberId(), muted);
        member.setMuted(muted);
        memberMapper.updateById(member);
        publishConferenceEvent("conference.member_muted", conference, member, Map.of("muted", muted));
        return refresh(conference);
    }

    @Override
    public CallConferenceResponse removeMember(String callId, Long memberRecordId) {
        return removeMember(null, callId, memberRecordId);
    }

    @Override
    public CallConferenceResponse removeMember(Long agentId, String callId, Long memberRecordId) {
        ConferenceManagementContext context = requireConferenceManagementContext(agentId, callId);
        return removeMember(context, memberRecordId);
    }

    private CallConferenceResponse removeMember(ConferenceManagementContext context, Long memberRecordId) {
        CallConference conference = context.conference();
        CallConferenceMember member = requireMember(conference, memberRecordId);
        if ("OWNER_AGENT".equals(member.getMemberRole()) || "CUSTOMER".equals(member.getMemberRole())
            || "COUNTERPARTY".equals(member.getMemberRole())) {
            throw new ServiceException("原通话成员不能从会议中移除");
        }
        requireLiveMemberId(member);
        commandGateway.removeConferenceMember(context.endpoint(), conference.getConferenceName(),
            member.getConferenceMemberId());
        member.setMemberState("LEFT");
        member.setLeftAt(LocalDateTime.now());
        memberMapper.updateById(member);
        publishConferenceEvent("conference.member_left", conference, member, Map.of("reason", "REMOVED"));
        return refresh(conference);
    }

    @Override
    public void leave(String callId) {
        leave(null, callId);
    }

    @Override
    public void leave(Long agentId, String callId) {
        ConferenceContext context = requireConferenceContext(agentId, callId, false);
        CallConference conference = requireActiveConference(context);
        CallConferenceResponse current = refresh(conference);
        long remainingJoinedMembers = current.getMembers().stream()
            .filter(member -> !"OWNER_AGENT".equals(member.getMemberRole()))
            .filter(member -> "JOINED".equals(member.getMemberState()))
            .count();
        if (remainingJoinedMembers < 2) {
            terminateConferenceAndMembers(context.endpoint(), conference);
            closeConference(conference);
            RedisUtils.deleteObject(activeCallKey(context.agent().getAgentId()));
            changeAgentStatus(context.agent(), AgentPresenceStatus.AFTER_CALL);
            log.info("会议发起坐席离开且剩余成员不足两人，已结束多方通话，businessCallId={}，conferenceName={}，remainingJoinedMembers={}",
                conference.getBusinessCallId(), conference.getConferenceName(), remainingJoinedMembers);
            publishConferenceEvent("conference.ended", conference, null, Map.of("reason", "INSUFFICIENT_MEMBERS"));
            return;
        }
        CallConferenceMember owner = memberMapper.selectOne(new LambdaQueryWrapper<CallConferenceMember>()
            .eq(CallConferenceMember::getConferenceId, conference.getId())
            .eq(CallConferenceMember::getMemberRole, "OWNER_AGENT")
            .last("limit 1"));
        if (owner == null || owner.getConferenceMemberId() == null) {
            commandGateway.hangup(context.endpoint(), context.ownerLeg().getLegUuid());
        } else {
            commandGateway.removeConferenceMember(context.endpoint(), conference.getConferenceName(),
                owner.getConferenceMemberId());
            owner.setMemberState("LEFT");
            owner.setLeftAt(LocalDateTime.now());
            memberMapper.updateById(owner);
        }
        RedisUtils.deleteObject(activeCallKey(context.agent().getAgentId()));
        changeAgentStatus(context.agent(), AgentPresenceStatus.AFTER_CALL);
        log.info("会议发起坐席已离开多方通话，businessCallId={}，conferenceName={}，ownerExtension={}",
            conference.getBusinessCallId(), conference.getConferenceName(), conference.getOwnerExtension());
        publishConferenceEvent("conference.member_left", conference, owner, Map.of("reason", "OWNER_LEFT"));
    }

    @Override
    public void end(String callId) {
        end(null, callId);
    }

    @Override
    public void end(Long agentId, String callId) {
        ConferenceManagementContext context = requireConferenceManagementContext(agentId, callId);
        endConference(context);
    }

    private void endConference(ConferenceManagementContext context) {
        CallConference conference = context.conference();
        terminateConferenceAndMembers(context.endpoint(), conference);
        closeConference(conference);
        clearConferenceAgentCalls(conference);
        changeAgentStatus(context.agent(), AgentPresenceStatus.AFTER_CALL);
        log.info("多方通话已由发起坐席结束，businessCallId={}，conferenceName={}，ownerExtension={}",
            conference.getBusinessCallId(), conference.getConferenceName(), conference.getOwnerExtension());
        publishConferenceEvent("conference.ended", conference, null, Map.of("reason", "OWNER_ENDED"));
    }

    @Override
    public boolean endIfActiveOwner(Long agentId, String callId) {
        CurrentAgentResponse agent = requireAgent(agentId);
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(agent.getAgentId()));
        if (activeCall == null || !matches(activeCall, callId)) {
            return false;
        }
        String businessCallId = firstNotBlank(activeCall.getBusinessCallId(), resolveBusinessCallId(activeCall), callId);
        CallConference conference = activeConference(businessCallId);
        if (conference == null || !Objects.equals(conference.getOwnerAgentId(), agent.getAgentId())) {
            return false;
        }
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        terminateConferenceAndMembers(endpoint, conference);
        closeConference(conference);
        clearConferenceAgentCalls(conference);
        changeAgentStatus(agent, AgentPresenceStatus.AFTER_CALL);
        log.info("通用挂机识别到坐席为会议发起人，已结束整个会议，businessCallId={}，conferenceName={}，ownerAgentId={}，ownerExtension={}",
            conference.getBusinessCallId(), conference.getConferenceName(), agent.getAgentId(), agent.getExtension());
        return true;
    }

    @Override
    public void handleMemberHangup(Long nodeId, String legUuid) {
        if (nodeId == null || legUuid == null || legUuid.isBlank()) {
            return;
        }
        CallConferenceMember matchedMember = TenantHelper.ignore(() -> memberMapper.selectOne(
            new LambdaQueryWrapper<CallConferenceMember>()
                .eq(CallConferenceMember::getLegUuid, legUuid)
                .in(CallConferenceMember::getMemberState, List.of("INVITING", "JOINED"))
                .orderByDesc(CallConferenceMember::getCreateTime)
                .last("limit 1")));
        if (matchedMember == null || matchedMember.getTenantId() == null) {
            return;
        }
        TenantHelper.dynamic(matchedMember.getTenantId(), () -> handleMemberHangupInTenant(nodeId, matchedMember));
    }

    private void handleMemberHangupInTenant(Long nodeId, CallConferenceMember member) {
        CallConference conference = conferenceMapper.selectById(member.getConferenceId());
        if (conference == null || !Objects.equals(nodeId, conference.getNodeId())
            || (!"CREATING".equals(conference.getConferenceState()) && !"ACTIVE".equals(conference.getConferenceState()))) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        member.setMemberState("LEFT");
        member.setLeftAt(now);
        memberMapper.updateById(member);

        List<CallConferenceMember> remaining = members(conference.getId()).stream()
            .filter(item -> "INVITING".equals(item.getMemberState()) || "JOINED".equals(item.getMemberState()))
            .toList();
        boolean ownerLeft = "OWNER_AGENT".equals(member.getMemberRole());
        if (remaining.size() >= 2) {
            return;
        }

        EslEndpoint endpoint = endpoint(conference.getNodeId());
        try {
            commandGateway.terminateConference(endpoint, conference.getConferenceName());
        } catch (RuntimeException exception) {
            log.warn("会议自动结束命令失败，尝试逐腿清理剩余成员，businessCallId={}，conferenceName={}，reason={}",
                conference.getBusinessCallId(), conference.getConferenceName(), exception.getMessage());
            for (CallConferenceMember remainingMember : remaining) {
                try {
                    if (commandGateway.callExists(endpoint, remainingMember.getLegUuid())) {
                        commandGateway.hangup(endpoint, remainingMember.getLegUuid());
                    }
                } catch (RuntimeException cleanupException) {
                    log.warn("会议残留成员电话腿清理失败，businessCallId={}，legUuid={}，reason={}",
                        conference.getBusinessCallId(), remainingMember.getLegUuid(), cleanupException.getMessage());
                }
            }
        }
        closeConference(conference);
        clearConferenceAgentCalls(conference);
        log.info("会议成员挂机触发自动结束，businessCallId={}，conferenceName={}，leftRole={}，leftLegUuid={}，remainingMembers={}",
            conference.getBusinessCallId(), conference.getConferenceName(), member.getMemberRole(), member.getLegUuid(),
            remaining.size());
    }

    private CallConferenceResponse refresh(CallConference conference) {
        if (!"ACTIVE".equals(conference.getConferenceState()) && !"CREATING".equals(conference.getConferenceState())) {
            return toResponse(conference, members(conference.getId()));
        }
        EslEndpoint endpoint = endpoint(conference.getNodeId());
        String raw;
        try {
            raw = commandGateway.conferenceMemberList(endpoint, conference.getConferenceName());
        } catch (RuntimeException exception) {
            log.warn("查询 FreeSWITCH 会议成员失败，暂时返回数据库状态，conferenceId={}，conferenceName={}，error={}",
                conference.getId(), conference.getConferenceName(), exception.getMessage());
            return toResponse(conference, members(conference.getId()));
        }
        if (raw == null || raw.isBlank() || raw.startsWith("-ERR")) {
            return toResponse(conference, members(conference.getId()));
        }
        syncLiveMembers(conference, parseLiveMembers(raw));
        return toResponse(conference, members(conference.getId()));
    }

    @SuppressWarnings("unchecked")
    private List<LiveConferenceMember> parseLiveMembers(String raw) {
        try {
            Object root;
            if (raw.stripLeading().startsWith("[")) {
                root = JsonUtils.parseArray(raw, Map.class);
            } else {
                root = JsonUtils.parseObject(raw, Map.class);
            }
            List<Map<String, Object>> conferenceObjects = new ArrayList<>();
            if (root instanceof List<?> list) {
                for (Object value : list) {
                    if (value instanceof Map<?, ?> map) {
                        conferenceObjects.add((Map<String, Object>) map);
                    }
                }
            } else if (root instanceof Map<?, ?> map) {
                conferenceObjects.add((Map<String, Object>) map);
            }
            List<LiveConferenceMember> result = new ArrayList<>();
            for (Map<String, Object> conferenceObject : conferenceObjects) {
                Object memberValue = firstValue(conferenceObject, "members", "conference_members");
                if (!(memberValue instanceof List<?> liveMembers)) {
                    continue;
                }
                for (Object value : liveMembers) {
                    if (!(value instanceof Map<?, ?> memberMap)) {
                        continue;
                    }
                    Map<String, Object> member = (Map<String, Object>) memberMap;
                    String id = stringValue(firstValue(member, "id", "member_id"));
                    String uuid = stringValue(firstValue(member, "uuid", "call_uuid"));
                    String number = stringValue(firstValue(member, "caller_id_number", "caller-id-number"));
                    String name = stringValue(firstValue(member, "caller_id_name", "caller-id-name"));
                    boolean muted = false;
                    Object flagsValue = member.get("flags");
                    if (flagsValue instanceof Map<?, ?> flags) {
                        Object canSpeak = firstValue((Map<String, Object>) flags, "can_speak", "can-speak");
                        muted = canSpeak != null && !booleanValue(canSpeak);
                    }
                    if (uuid != null && id != null) {
                        result.add(new LiveConferenceMember(id, uuid, number, name, muted));
                    }
                }
            }
            return result;
        } catch (Exception exception) {
            log.warn("解析 FreeSWITCH 会议成员 JSON 失败，raw={}，error={}", raw, exception.getMessage());
            return List.of();
        }
    }

    private void syncLiveMembers(CallConference conference, List<LiveConferenceMember> liveMembers) {
        if (liveMembers.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, LiveConferenceMember> liveByUuid = new HashMap<>();
        liveMembers.forEach(member -> liveByUuid.put(member.uuid(), member));
        List<CallConferenceMember> persisted = members(conference.getId());
        Map<String, CallConferenceMember> persistedByUuid = new LinkedHashMap<>();
        persisted.forEach(member -> persistedByUuid.put(member.getLegUuid(), member));
        for (LiveConferenceMember live : liveMembers) {
            CallConferenceMember member = persistedByUuid.get(live.uuid());
            if (member == null) {
                member = new CallConferenceMember();
                member.setConferenceId(conference.getId());
                member.setSessionId(conference.getSessionId());
                member.setBusinessCallId(conference.getBusinessCallId());
                member.setLegUuid(live.uuid());
                member.setMemberRole("INVITED_EXTENSION");
                member.setExtension(live.number());
                member.setDisplayName(firstNotBlank(live.name(), live.number(), "会议成员"));
                member.setInvitedAt(now);
                member.setMemberState("JOINED");
                member.setJoinedAt(now);
                member.setConferenceMemberId(live.id());
                member.setMuted(live.muted());
                memberMapper.insert(member);
                publishConferenceEvent("conference.member_joined", conference, member, Map.of());
                continue;
            }
            boolean newlyJoined = !"JOINED".equals(member.getMemberState());
            member.setConferenceMemberId(live.id());
            member.setMemberState("JOINED");
            member.setMuted(live.muted());
            member.setFailureReason(null);
            if (member.getJoinedAt() == null) {
                member.setJoinedAt(now);
            }
            memberMapper.updateById(member);
            if (newlyJoined) {
                publishConferenceEvent("conference.member_joined", conference, member, Map.of());
            }
        }
        for (CallConferenceMember member : persisted) {
            if ("JOINED".equals(member.getMemberState()) && !liveByUuid.containsKey(member.getLegUuid())) {
                member.setMemberState("LEFT");
                member.setLeftAt(now);
                memberMapper.updateById(member);
            }
        }
        syncStandaloneSession(conference, liveMembers.size(), now);
    }

    private void prepareConferenceLeg(EslEndpoint endpoint, String legUuid) {
        commandGateway.setCallVariable(endpoint, legUuid, "hangup_after_bridge", "false");
        commandGateway.setCallVariable(endpoint, legUuid, "park_after_bridge", "false");
        try {
            commandGateway.unhold(endpoint, legUuid);
        } catch (RuntimeException exception) {
            log.debug("会议电话腿当前不在保持状态，继续升级会议，legUuid={}，reason={}", legUuid, exception.getMessage());
        }
        try {
            commandGateway.recoverMedia(endpoint, legUuid);
        } catch (RuntimeException exception) {
            log.warn("会议电话腿媒体恢复失败，继续提交会议命令，legUuid={}，reason={}", legUuid, exception.getMessage());
        }
    }

    private void requireLiveLeg(EslEndpoint endpoint, String legUuid, String legName) {
        if (legUuid == null || legUuid.isBlank() || !commandGateway.callExists(endpoint, legUuid)) {
            throw new ServiceException(legName + "不存在或已结束");
        }
    }

    private CallLeg requireCallLeg(String businessCallId, String legUuid, String legName) {
        CallLeg leg = legMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getBusinessCallId, businessCallId)
            .eq(CallLeg::getLegUuid, legUuid)
            .last("limit 1"));
        if (leg == null) {
            throw new ServiceException("无法在业务通话中定位" + legName);
        }
        return leg;
    }

    private boolean matchesConsult(AgentConsultCall consultCall, String callId) {
        return callId != null && (callId.equals(consultCall.getBusinessCallId())
            || callId.equals(consultCall.getOriginalCallId())
            || callId.equals(consultCall.getCustomerCallId())
            || callId.equals(consultCall.getSourceAgentCallId())
            || callId.equals(consultCall.getTargetAgentCallId())
            || callId.equals(consultCall.getConsultCallId()));
    }

    private boolean isConsultTransitionInProgressOrEnded(AgentConsultCallStatus status) {
        return status == AgentConsultCallStatus.COMPLETING
            || status == AgentConsultCallStatus.COMPLETED
            || status == AgentConsultCallStatus.CANCELLING
            || status == AgentConsultCallStatus.CANCELLED
            || status == AgentConsultCallStatus.FAILED;
    }

    private void saveConsultState(CurrentAgentResponse agent, AgentConsultCall consultCall) {
        RedisUtils.setCacheObject(consultCallKey(agent.getAgentId()), consultCall, ACTIVE_CALL_TTL);
        saveConsultLegIndex(consultCall.getCustomerLegUuid(), consultCall);
        saveConsultLegIndex(consultCall.getSourceAgentLegUuid(), consultCall);
        saveConsultLegIndex(consultCall.getConsultLegUuid(), consultCall);
    }

    private void saveConsultLegIndex(String legUuid, AgentConsultCall consultCall) {
        if (legUuid != null && !legUuid.isBlank()) {
            RedisUtils.setCacheObject(consultLegKey(legUuid), consultCall, ACTIVE_CALL_TTL);
        }
    }

    private void deleteConsultState(CurrentAgentResponse agent, AgentConsultCall consultCall) {
        RedisUtils.deleteObject(consultCallKey(agent.getAgentId()));
        deleteConsultLegIndex(consultCall.getCustomerLegUuid());
        deleteConsultLegIndex(consultCall.getSourceAgentLegUuid());
        deleteConsultLegIndex(consultCall.getConsultLegUuid());
    }

    private void deleteConsultLegIndex(String legUuid) {
        if (legUuid != null && !legUuid.isBlank()) {
            RedisUtils.deleteObject(consultLegKey(legUuid));
        }
    }

    private ConferenceContext requireConferenceContext(Long agentId, String callId, boolean rejectConsult) {
        CurrentAgentResponse agent = requireAgent(agentId);
        if (!agent.isConfigured() || agent.getAgentId() == null || agent.getNodeId() == null
            || agent.getExtension() == null || agent.getExtension().isBlank()) {
            throw new ServiceException("当前用户未绑定可用坐席分机");
        }
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(agent.getAgentId()));
        if (activeCall == null || !matches(activeCall, callId)) {
            throw new ServiceException("当前通话不存在或已结束");
        }
        if (rejectConsult) {
            AgentConsultCall consultCall = RedisUtils.getCacheObject(consultCallKey(agent.getAgentId()));
            if (consultCall != null) {
                throw new ServiceException("咨询转接期间不能发起多方通话");
            }
        }
        String businessCallId = firstNotBlank(activeCall.getBusinessCallId(), resolveBusinessCallId(activeCall), callId);
        CallSession session = sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .last("limit 1"));
        if (session == null || "ENDED".equals(session.getCallStatus())) {
            throw new ServiceException("当前业务通话不存在或已结束");
        }
        List<CallLeg> activeLegs = legMapper.selectList(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getBusinessCallId, businessCallId)
            .eq(CallLeg::getActive, true));
        EslEndpoint endpoint = endpoint(agent.getNodeId());
        List<CallLeg> liveLegs = activeLegs.stream()
            .filter(leg -> commandGateway.callExists(endpoint, leg.getLegUuid()))
            .toList();
        CallLeg ownerLeg = liveLegs.stream()
            .filter(leg -> Objects.equals(agent.getAgentId(), leg.getAgentId())
                || agent.getExtension().equals(memberExtension(leg)))
            .findFirst()
            .orElseThrow(() -> new ServiceException("无法定位当前坐席电话腿"));
        CallLeg counterpartyLeg = liveLegs.stream()
            .filter(leg -> !ownerLeg.getLegUuid().equals(leg.getLegUuid()))
            .findFirst()
            .orElseThrow(() -> new ServiceException("无法定位当前通话对端电话腿"));
        return new ConferenceContext(agent, activeCall, session, businessCallId, ownerLeg, counterpartyLeg,
            liveLegs, endpoint);
    }

    private CallConference requireActiveConference(ConferenceContext context) {
        CallConference conference = activeConference(context.businessCallId());
        if (conference == null) {
            throw new ServiceException("当前通话尚未创建多方会议");
        }
        assertOwner(conference, context.agent());
        return conference;
    }

    private ConferenceManagementContext requireConferenceManagementContext(Long agentId, String callId) {
        CurrentAgentResponse agent = requireAgent(agentId);
        CallConference conference = findActiveConferenceForManagement(agent, callId);
        if (conference == null) {
            throw new ServiceException("当前通话尚未创建多方会议或会议已结束");
        }
        assertOwner(conference, agent);
        return new ConferenceManagementContext(agent, conference, endpoint(conference.getNodeId()));
    }

    private ConferenceManagementContext requireConferenceManagementContextById(Long agentId, Long conferenceId) {
        if (conferenceId == null) {
            throw new ServiceException("会议ID不能为空");
        }
        CurrentAgentResponse agent = requireAgent(agentId);
        CallConference conference = conferenceMapper.selectById(conferenceId);
        if (conference == null || (!"CREATING".equals(conference.getConferenceState())
            && !"ACTIVE".equals(conference.getConferenceState()))) {
            throw new ServiceException("会议不存在或已结束");
        }
        assertOwner(conference, agent);
        return new ConferenceManagementContext(agent, conference, endpoint(conference.getNodeId()));
    }

    private CallConference findActiveConferenceForManagement(CurrentAgentResponse agent, String callId) {
        if (callId == null || callId.isBlank()) {
            return null;
        }
        CallConference conference = activeConference(callId);
        if (conference != null) {
            return conference;
        }
        AgentActiveCall activeCall = RedisUtils.getCacheObject(activeCallKey(agent.getAgentId()));
        if (activeCall == null || !matches(activeCall, callId)) {
            return null;
        }
        String businessCallId = firstNotBlank(activeCall.getBusinessCallId(), resolveBusinessCallId(activeCall), callId);
        return activeConference(businessCallId);
    }

    private CallConference activeConference(String businessCallId) {
        return conferenceMapper.selectOne(new LambdaQueryWrapper<CallConference>()
            .eq(CallConference::getBusinessCallId, businessCallId)
            .in(CallConference::getConferenceState, List.of("CREATING", "ACTIVE"))
            .orderByDesc(CallConference::getCreateTime)
            .last("limit 1"));
    }

    private void assertOwner(CallConference conference, CurrentAgentResponse agent) {
        if (!Objects.equals(conference.getOwnerAgentId(), agent.getAgentId())) {
            throw new ServiceException("仅会议发起坐席可以管理当前多方通话");
        }
    }

    private CallConferenceMember requireMember(CallConference conference, Long memberRecordId) {
        if (memberRecordId == null) {
            throw new ServiceException("会议成员不能为空");
        }
        CallConferenceMember member = memberMapper.selectById(memberRecordId);
        if (member == null || !conference.getId().equals(member.getConferenceId())) {
            throw new ServiceException("会议成员不存在");
        }
        return member;
    }

    private void requireLiveMemberId(CallConferenceMember member) {
        if (member.getConferenceMemberId() == null || member.getConferenceMemberId().isBlank()
            || !"JOINED".equals(member.getMemberState())) {
            throw new ServiceException("会议成员尚未接通或已离开");
        }
    }

    private void closeConference(CallConference conference) {
        LocalDateTime now = LocalDateTime.now();
        conference.setConferenceState("ENDED");
        conference.setEndedAt(now);
        conferenceMapper.updateById(conference);
        for (CallConferenceMember member : members(conference.getId())) {
            if (!"LEFT".equals(member.getMemberState()) && !"FAILED".equals(member.getMemberState())) {
                member.setMemberState("LEFT");
                member.setLeftAt(now);
                memberMapper.updateById(member);
            }
        }
        closeStandaloneSession(conference, now);
    }

    private void markStandaloneSessionRinging(CallSession session, LocalDateTime now) {
        if (!isStandaloneConferenceSession(session)) {
            return;
        }
        session.setCallStatus("RINGING");
        if (session.getRingingAt() == null) {
            session.setRingingAt(now);
        }
        sessionMapper.updateById(session);
    }

    private void syncStandaloneSession(CallConference conference, int liveMemberCount, LocalDateTime now) {
        CallSession session = sessionMapper.selectById(conference.getSessionId());
        if (!isStandaloneConferenceSession(session) || "ENDED".equals(session.getCallStatus())) {
            return;
        }
        if (liveMemberCount >= 2) {
            session.setCallStatus("BRIDGED");
            session.setCurrentBridgeState("BRIDGED");
        } else {
            session.setCallStatus("ANSWERED");
        }
        if (session.getAnsweredAt() == null) {
            session.setAnsweredAt(now);
        }
        sessionMapper.updateById(session);
    }

    private void closeStandaloneSession(CallConference conference, LocalDateTime now) {
        CallSession session = sessionMapper.selectById(conference.getSessionId());
        if (!isStandaloneConferenceSession(session) || "ENDED".equals(session.getCallStatus())) {
            return;
        }
        session.setCallStatus("ENDED");
        session.setCurrentBridgeState("UNBRIDGED");
        session.setEndedAt(now);
        session.setHangupCause("NORMAL_CLEARING");
        session.setDurationSeconds((int) Duration.between(session.getStartedAt(), now).toSeconds());
        session.setBillableSeconds(session.getAnsweredAt() == null
            ? 0
            : (int) Duration.between(session.getAnsweredAt(), now).toSeconds());
        sessionMapper.updateById(session);
    }

    private boolean isStandaloneConferenceSession(CallSession session) {
        return session != null && "CONFERENCE".equals(session.getCalledNumber());
    }

    private void publishConferenceEvent(String eventType, CallConference conference,
                                        CallConferenceMember member, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conference_id", conference.getId());
        payload.put("conference_name", conference.getConferenceName());
        payload.put("display_name", conference.getDisplayName());
        payload.put("conference_state", conference.getConferenceState());
        payload.put("owner_agent_id", conference.getOwnerAgentId());
        payload.put("owner_extension", conference.getOwnerExtension());
        if (member != null) {
            payload.put("member_id", member.getId());
            payload.put("member_role", member.getMemberRole());
            payload.put("extension", member.getExtension());
            payload.put("member_state", member.getMemberState());
        }
        payload.putAll(details);
        applicationEventPublisher.publishEvent(new CallLifecycleEvent(TenantHelper.getTenantId(), eventType,
            conference.getBusinessCallId(), conference.getNodeId(), LocalDateTime.now(), payload));
    }

    private void clearConferenceAgentCalls(CallConference conference) {
        for (CallConferenceMember member : members(conference.getId())) {
            if (member.getAgentId() != null) {
                RedisUtils.deleteObject(activeCallKey(member.getAgentId()));
            }
        }
    }

    private void leaveConference(ConferenceManagementContext context) {
        CallConference conference = context.conference();
        CallConferenceResponse current = refresh(conference);
        long remainingJoinedMembers = current.getMembers().stream()
            .filter(member -> !"OWNER_AGENT".equals(member.getMemberRole()))
            .filter(member -> "JOINED".equals(member.getMemberState()))
            .count();
        if (remainingJoinedMembers < 2) {
            terminateConferenceAndMembers(context.endpoint(), conference);
            closeConference(conference);
            clearConferenceAgentCalls(conference);
            changeAgentStatus(context.agent(), AgentPresenceStatus.AFTER_CALL);
            return;
        }
        CallConferenceMember owner = memberMapper.selectOne(new LambdaQueryWrapper<CallConferenceMember>()
            .eq(CallConferenceMember::getConferenceId, conference.getId())
            .eq(CallConferenceMember::getMemberRole, "OWNER_AGENT")
            .last("limit 1"));
        if (owner == null) {
            throw new ServiceException("会议发起坐席成员不存在");
        }
        if (owner.getConferenceMemberId() != null && !owner.getConferenceMemberId().isBlank()) {
            commandGateway.removeConferenceMember(context.endpoint(), conference.getConferenceName(),
                owner.getConferenceMemberId());
        } else if (commandGateway.callExists(context.endpoint(), owner.getLegUuid())) {
            commandGateway.hangup(context.endpoint(), owner.getLegUuid());
        }
        owner.setMemberState("LEFT");
        owner.setLeftAt(LocalDateTime.now());
        memberMapper.updateById(owner);
        RedisUtils.deleteObject(activeCallKey(context.agent().getAgentId()));
        changeAgentStatus(context.agent(), AgentPresenceStatus.AFTER_CALL);
    }

    private void terminateConferenceAndMembers(EslEndpoint endpoint, CallConference conference) {
        try {
            commandGateway.terminateConference(endpoint, conference.getConferenceName());
        } catch (RuntimeException exception) {
            log.warn("结束会议房间失败，继续逐腿清理，conferenceId={}，businessCallId={}，conferenceName={}，reason={}",
                conference.getId(), conference.getBusinessCallId(), conference.getConferenceName(),
                exception.getMessage());
        }
        for (CallConferenceMember member : members(conference.getId())) {
            if (!"INVITING".equals(member.getMemberState()) && !"JOINED".equals(member.getMemberState())) {
                continue;
            }
            try {
                if (commandGateway.callExists(endpoint, member.getLegUuid())) {
                    commandGateway.hangup(endpoint, member.getLegUuid());
                }
            } catch (RuntimeException exception) {
                log.warn("结束会议清理成员电话腿失败，conferenceId={}，businessCallId={}，legUuid={}，extension={}，reason={}",
                    conference.getId(), conference.getBusinessCallId(), member.getLegUuid(), member.getExtension(),
                    exception.getMessage());
            }
        }
    }

    private List<CallConferenceMember> members(Long conferenceId) {
        return memberMapper.selectList(new LambdaQueryWrapper<CallConferenceMember>()
            .eq(CallConferenceMember::getConferenceId, conferenceId)
            .orderByAsc(CallConferenceMember::getCreateTime));
    }

    private CallConferenceMember newMember(CallConference conference, CallLeg leg, String role, Long agentId,
                                           String extension, String displayName, LocalDateTime now) {
        CallConferenceMember member = new CallConferenceMember();
        member.setConferenceId(conference.getId());
        member.setSessionId(conference.getSessionId());
        member.setBusinessCallId(conference.getBusinessCallId());
        member.setLegUuid(leg.getLegUuid());
        member.setMemberRole(role);
        member.setAgentId(agentId);
        member.setExtension(extension);
        member.setDisplayName(displayName);
        member.setMemberState("INVITING");
        member.setMuted(false);
        member.setInvitedAt(now);
        return member;
    }

    private CallConferenceMember standaloneMember(CallConference conference, String role, Long agentId,
                                                  String extension, String displayName, LocalDateTime now) {
        CallConferenceMember member = new CallConferenceMember();
        member.setConferenceId(conference.getId());
        member.setSessionId(conference.getSessionId());
        member.setBusinessCallId(conference.getBusinessCallId());
        member.setLegUuid(UUID.randomUUID().toString());
        member.setMemberRole(role);
        member.setAgentId(agentId);
        member.setExtension(extension);
        member.setDisplayName(displayName);
        member.setMemberState("INVITING");
        member.setMuted(false);
        member.setInvitedAt(now);
        return member;
    }

    private List<String> normalizeExtensions(List<String> targetExtensions) {
        if (targetExtensions == null || targetExtensions.isEmpty()) {
            return List.of();
        }
        return targetExtensions.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(extension -> !extension.isBlank())
            .distinct()
            .toList();
    }

    private void markStandaloneConferenceFailed(CallSession session, CallConference conference,
                                                CallConferenceMember owner, RuntimeException exception) {
        LocalDateTime now = LocalDateTime.now();
        owner.setMemberState("FAILED");
        owner.setFailureReason(exception.getMessage());
        owner.setLeftAt(now);
        memberMapper.updateById(owner);
        conference.setConferenceState("FAILED");
        conference.setFailureReason(exception.getMessage());
        conference.setEndedAt(now);
        conferenceMapper.updateById(conference);
        session.setCallStatus("ENDED");
        session.setEndedAt(now);
        session.setHangupCause("ORIGINATOR_CANCEL");
        session.setDurationSeconds((int) Duration.between(session.getStartedAt(), now).toSeconds());
        sessionMapper.updateById(session);
    }

    private CallConferenceResponse toResponse(CallConference conference, List<CallConferenceMember> members) {
        CallConferenceResponse response = new CallConferenceResponse();
        response.setId(conference.getId());
        response.setSessionId(conference.getSessionId());
        response.setBusinessCallId(conference.getBusinessCallId());
        response.setNodeId(conference.getNodeId());
        response.setConferenceName(conference.getConferenceName());
        response.setDisplayName(conference.getDisplayName());
        response.setOwnerAgentId(conference.getOwnerAgentId());
        response.setOwnerExtension(conference.getOwnerExtension());
        response.setConferenceState(conference.getConferenceState());
        response.setStartedAt(conference.getStartedAt());
        response.setEndedAt(conference.getEndedAt());
        response.setMembers(members.stream().map(this::toMemberResponse).toList());
        return response;
    }

    private CallConferenceMemberResponse toMemberResponse(CallConferenceMember member) {
        CallConferenceMemberResponse response = new CallConferenceMemberResponse();
        response.setId(member.getId());
        response.setLegUuid(member.getLegUuid());
        response.setConferenceMemberId(member.getConferenceMemberId());
        response.setMemberRole(member.getMemberRole());
        response.setAgentId(member.getAgentId());
        response.setExtension(member.getExtension());
        response.setDisplayName(member.getDisplayName());
        response.setMemberState(member.getMemberState());
        response.setMuted(member.getMuted());
        response.setJoinedAt(member.getJoinedAt());
        response.setLeftAt(member.getLeftAt());
        return response;
    }

    private String resolveBusinessCallId(AgentActiveCall activeCall) {
        List<String> candidates = new ArrayList<>();
        candidates.add(activeCall.getCallId());
        if (activeCall.getRelatedUuids() != null) {
            candidates.addAll(activeCall.getRelatedUuids());
        }
        for (String uuid : candidates) {
            if (uuid == null || uuid.isBlank()) {
                continue;
            }
            CallLeg leg = legMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
                .select(CallLeg::getBusinessCallId)
                .eq(CallLeg::getLegUuid, uuid)
                .last("limit 1"));
            if (leg != null && leg.getBusinessCallId() != null && !leg.getBusinessCallId().isBlank()) {
                return leg.getBusinessCallId();
            }
        }
        return null;
    }

    private boolean matches(AgentActiveCall activeCall, String callId) {
        if (callId == null || callId.isBlank()) {
            return false;
        }
        return callId.equals(activeCall.getCallId())
            || callId.equals(activeCall.getBusinessCallId())
            || activeCall.getRelatedUuids() != null && activeCall.getRelatedUuids().contains(callId);
    }

    private String counterpartyName(CallLeg leg, CallSession session) {
        if ("CUSTOMER".equals(leg.getLegRole())) {
            return firstNotBlank(session.getCallerNumber(), leg.getCallerNumber(), "客户");
        }
        return firstNotBlank(memberExtension(leg), leg.getCalledNumber(), "通话对端");
    }

    private String memberExtension(CallLeg leg) {
        return firstNotBlank(leg.getAgentExtension(), leg.getEndpointExtension());
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

    private String activeCallKey(Long agentId) {
        return ACTIVE_CALL_KEY_PREFIX + TenantHelper.getTenantId() + ":" + agentId;
    }

    private String consultCallKey(Long agentId) {
        return CONSULT_CALL_KEY_PREFIX + TenantHelper.getTenantId() + ":" + agentId;
    }

    private String consultLegKey(String legUuid) {
        return CONSULT_LEG_KEY_PREFIX + legUuid;
    }

    private CurrentAgentResponse requireAgent(Long agentId) {
        CurrentAgentResponse agent = agentId == null
            ? agentSessionService.current()
            : explicitAgentSessionService.get(agentId);
        if (!agent.isConfigured() || agent.getAgentId() == null || agent.getNodeId() == null
            || agent.getExtension() == null || agent.getExtension().isBlank()) {
            throw new ServiceException("当前用户未绑定可用坐席分机");
        }
        if (agent.getStatus() == AgentPresenceStatus.OFFLINE) {
            throw new ServiceException("坐席未签入，请先签入");
        }
        return agent;
    }

    private void changeAgentStatus(CurrentAgentResponse agent, AgentPresenceStatus status) {
        explicitAgentSessionService.changeStatus(agent.getAgentId(), status);
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record ConferenceContext(CurrentAgentResponse agent, AgentActiveCall activeCall, CallSession session,
                                     String businessCallId, CallLeg ownerLeg, CallLeg counterpartyLeg,
                                     List<CallLeg> liveLegs, EslEndpoint endpoint) {
    }

    private record ConferenceManagementContext(CurrentAgentResponse agent, CallConference conference,
                                               EslEndpoint endpoint) {
    }

    private record LiveConferenceMember(String id, String uuid, String number, String name, boolean muted) {
    }
}
