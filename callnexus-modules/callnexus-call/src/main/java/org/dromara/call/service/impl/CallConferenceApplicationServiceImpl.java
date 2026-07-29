package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentActiveCall;
import org.dromara.agent.domain.AgentConsultCall;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.call.domain.CallConference;
import org.dromara.call.domain.CallConferenceMember;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.EslEndpoint;
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
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Service;

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
    private static final Duration ACTIVE_CALL_TTL = Duration.ofHours(4);

    private final CurrentAgentSessionService agentSessionService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway commandGateway;
    private final CallSessionMapper sessionMapper;
    private final CallLegMapper legMapper;
    private final CallConferenceMapper conferenceMapper;
    private final CallConferenceMemberMapper memberMapper;

    @Override
    public CallConferenceResponse create(String callId) {
        ConferenceContext context = requireConferenceContext(callId, true);
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
                LoginHelper.getTenantId(), context.businessCallId(), context.session().getId(),
                context.agent().getNodeId(), conference.getConferenceName(),
                context.ownerLeg().getLegUuid(), context.counterpartyLeg().getLegUuid());
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
        ConferenceContext context = requireConferenceContext(callId, false);
        CallConference conference = activeConference(context.businessCallId());
        if (conference == null) {
            return null;
        }
        assertOwner(conference, context.agent());
        return refresh(conference);
    }

    @Override
    public CallConferenceResponse invite(String callId, String targetExtension) {
        ConferenceContext context = requireConferenceContext(callId, false);
        CallConference conference = requireActiveConference(context);
        String normalizedExtension = targetExtension == null ? null : targetExtension.trim();
        if (normalizedExtension == null || normalizedExtension.isBlank()) {
            throw new ServiceException("目标分机不能为空");
        }
        if (normalizedExtension.equals(context.agent().getExtension())) {
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
            commandGateway.originateConferenceParticipant(context.endpoint(), conference.getBusinessCallId(), legUuid,
                conference.getConferenceName(), normalizedExtension, context.agent().getExtension());
            log.info("多方会议邀请已提交，tenantId={}，businessCallId={}，conferenceId={}，conferenceName={}，targetExtension={}，participantLegUuid={}",
                LoginHelper.getTenantId(), conference.getBusinessCallId(), conference.getId(),
                conference.getConferenceName(), normalizedExtension, legUuid);
        } catch (RuntimeException exception) {
            member.setMemberState("FAILED");
            member.setFailureReason(exception.getMessage());
            member.setLeftAt(LocalDateTime.now());
            memberMapper.updateById(member);
            throw exception;
        }
        return refresh(conference);
    }

    @Override
    public CallConferenceResponse muteMember(String callId, Long memberRecordId, boolean muted) {
        ConferenceContext context = requireConferenceContext(callId, false);
        CallConference conference = requireActiveConference(context);
        CallConferenceMember member = requireMember(conference, memberRecordId);
        if ("OWNER_AGENT".equals(member.getMemberRole())) {
            throw new ServiceException("当前坐席请使用通话静音，不通过会议成员控制");
        }
        requireLiveMemberId(member);
        commandGateway.muteConferenceMember(context.endpoint(), conference.getConferenceName(),
            member.getConferenceMemberId(), muted);
        member.setMuted(muted);
        memberMapper.updateById(member);
        return refresh(conference);
    }

    @Override
    public CallConferenceResponse removeMember(String callId, Long memberRecordId) {
        ConferenceContext context = requireConferenceContext(callId, false);
        CallConference conference = requireActiveConference(context);
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
        return refresh(conference);
    }

    @Override
    public void leave(String callId) {
        ConferenceContext context = requireConferenceContext(callId, false);
        CallConference conference = requireActiveConference(context);
        CallConferenceResponse current = refresh(conference);
        long remainingJoinedMembers = current.getMembers().stream()
            .filter(member -> !"OWNER_AGENT".equals(member.getMemberRole()))
            .filter(member -> "JOINED".equals(member.getMemberState()))
            .count();
        if (remainingJoinedMembers < 2) {
            commandGateway.terminateConference(context.endpoint(), conference.getConferenceName());
            closeConference(conference);
            RedisUtils.deleteObject(activeCallKey(context.agent().getAgentId()));
            agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
            log.info("会议发起坐席离开且剩余成员不足两人，已结束多方通话，businessCallId={}，conferenceName={}，remainingJoinedMembers={}",
                conference.getBusinessCallId(), conference.getConferenceName(), remainingJoinedMembers);
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
        agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
        log.info("会议发起坐席已离开多方通话，businessCallId={}，conferenceName={}，ownerExtension={}",
            conference.getBusinessCallId(), conference.getConferenceName(), conference.getOwnerExtension());
    }

    @Override
    public void end(String callId) {
        ConferenceContext context = requireConferenceContext(callId, false);
        CallConference conference = requireActiveConference(context);
        commandGateway.terminateConference(context.endpoint(), conference.getConferenceName());
        closeConference(conference);
        RedisUtils.deleteObject(activeCallKey(context.agent().getAgentId()));
        agentSessionService.changeStatus(AgentPresenceStatus.AFTER_CALL);
        log.info("多方通话已由发起坐席结束，businessCallId={}，conferenceName={}，ownerExtension={}",
            conference.getBusinessCallId(), conference.getConferenceName(), conference.getOwnerExtension());
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
                continue;
            }
            member.setConferenceMemberId(live.id());
            member.setMemberState("JOINED");
            member.setMuted(live.muted());
            member.setFailureReason(null);
            if (member.getJoinedAt() == null) {
                member.setJoinedAt(now);
            }
            memberMapper.updateById(member);
        }
        for (CallConferenceMember member : persisted) {
            if ("JOINED".equals(member.getMemberState()) && !liveByUuid.containsKey(member.getLegUuid())) {
                member.setMemberState("LEFT");
                member.setLeftAt(now);
                memberMapper.updateById(member);
            }
        }
    }

    private ConferenceContext requireConferenceContext(String callId, boolean rejectConsult) {
        CurrentAgentResponse agent = agentSessionService.current();
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

    private CallConferenceResponse toResponse(CallConference conference, List<CallConferenceMember> members) {
        CallConferenceResponse response = new CallConferenceResponse();
        response.setId(conference.getId());
        response.setSessionId(conference.getSessionId());
        response.setBusinessCallId(conference.getBusinessCallId());
        response.setNodeId(conference.getNodeId());
        response.setConferenceName(conference.getConferenceName());
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
        return ACTIVE_CALL_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
    }

    private String consultCallKey(Long agentId) {
        return CONSULT_CALL_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agentId;
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

    private record LiveConferenceMember(String id, String uuid, String number, String name, boolean muted) {
    }
}
