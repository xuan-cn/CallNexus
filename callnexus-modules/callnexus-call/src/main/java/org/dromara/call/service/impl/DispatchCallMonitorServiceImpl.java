package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.AgentCallSession;
import org.dromara.call.domain.CallBridge;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.response.AgentCallSessionResponse;
import org.dromara.call.domain.response.CallDiagnosticBridgeResponse;
import org.dromara.call.domain.response.CallDiagnosticLegResponse;
import org.dromara.call.domain.response.DispatchActiveCallResponse;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;
import org.dromara.call.domain.response.DispatchExtensionStatusResponse;
import org.dromara.call.mapper.AgentCallSessionMapper;
import org.dromara.call.mapper.CallBridgeMapper;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.sip.domain.SipAccount;
import org.dromara.resource.sip.mapper.SipAccountMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchCallMonitorServiceImpl implements DispatchCallMonitorService {
    private static final int MAX_ACTIVE_CALLS = 500;
    private static final String PRESENCE_KEY_PREFIX = "callnexus:agent:presence:";

    private final CallSessionMapper sessionMapper;
    private final CallLegMapper legMapper;
    private final CallBridgeMapper bridgeMapper;
    private final AgentCallSessionMapper agentCallSessionMapper;
    private final SipAccountMapper sipAccountMapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final AgentExtensionMapper agentExtensionMapper;
    private final AgentMapper agentMapper;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway commandGateway;

    @Override
    public List<DispatchExtensionStatusResponse> listExtensionStatuses() {
        List<SipAccount> accounts = sipAccountMapper.selectList(new LambdaQueryWrapper<SipAccount>()
            .orderByAsc(SipAccount::getNodeId)
            .orderByAsc(SipAccount::getExtension));
        if (accounts.isEmpty()) {
            return List.of();
        }

        Map<Long, FreeSwitchNode> nodes = nodeMapper.selectBatchIds(accounts.stream()
                .map(SipAccount::getNodeId).filter(java.util.Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(FreeSwitchNode::getId, Function.identity()));
        Map<Long, AgentExtension> bindings = agentExtensionMapper.selectList(new LambdaQueryWrapper<AgentExtension>()
                .in(AgentExtension::getSipAccountId, accounts.stream().map(SipAccount::getId).toList()))
            .stream().collect(Collectors.toMap(AgentExtension::getSipAccountId, Function.identity(), (left, right) -> left));
        List<Long> agentIds = bindings.values().stream()
            .map(AgentExtension::getAgentId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Agent> agents = agentIds.isEmpty() ? Map.of() : agentMapper.selectBatchIds(agentIds)
            .stream().collect(Collectors.toMap(Agent::getId, Function.identity()));
        Map<Long, Set<String>> registrations = loadRegistrations(nodes.keySet());

        List<CallLeg> activeAgentLegs = legMapper.selectList(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getActive, true)
            .isNull(CallLeg::getEndedAt)
            .in(CallLeg::getLegRole, List.of("AGENT", "CONSULT_AGENT"))
            .isNotNull(CallLeg::getAgentExtension));
        Map<String, CallLeg> activeLegByExtension = activeAgentLegs.stream()
            .collect(Collectors.toMap(leg -> extensionKey(leg.getNodeId(), leg.getAgentExtension()), Function.identity(),
                this::preferActiveLeg));

        return accounts.stream().map(account -> {
            DispatchExtensionStatusResponse response = new DispatchExtensionStatusResponse();
            response.setSipAccountId(account.getId());
            response.setNodeId(account.getNodeId());
            FreeSwitchNode node = nodes.get(account.getNodeId());
            response.setNodeName(node == null ? null : node.getNodeName());
            response.setExtension(account.getExtension());
            response.setDisplayName(account.getDisplayName());
            response.setDomain(account.getDomain());
            response.setEnabled(account.getEnabled());
            if (!Boolean.TRUE.equals(account.getEnabled())) {
                response.setRegistrationStatus("DISABLED");
            } else if (!registrations.containsKey(account.getNodeId())) {
                response.setRegistrationStatus("NODE_UNAVAILABLE");
            } else {
                response.setRegistrationStatus(registrations.get(account.getNodeId()).contains(account.getExtension())
                    ? "REGISTERED" : "UNREGISTERED");
            }
            AgentExtension binding = bindings.get(account.getId());
            Agent agent = binding == null ? null : agents.get(binding.getAgentId());
            if (agent != null) {
                response.setAgentId(agent.getId());
                response.setAgentName(agent.getAgentName());
                AgentPresence presence = RedisUtils.getCacheObject(PRESENCE_KEY_PREFIX + LoginHelper.getTenantId() + ":" + agent.getId());
                response.setAgentPresenceStatus(presence == null ? "OFFLINE" : presence.getStatus().name());
            }
            CallLeg activeLeg = activeLegByExtension.get(extensionKey(account.getNodeId(), account.getExtension()));
            response.setCallStatus(resolveCallStatus(activeLeg));
            response.setBusinessCallId(activeLeg == null ? null : activeLeg.getBusinessCallId());
            return response;
        }).toList();
    }

    @Override
    public List<DispatchActiveCallResponse> listActiveCalls() {
        List<CallSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<CallSession>()
            .isNull(CallSession::getEndedAt)
            .ne(CallSession::getCallStatus, "ENDED")
            .orderByDesc(CallSession::getStartedAt)
            .last("limit " + MAX_ACTIVE_CALLS));
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<Long> sessionIds = sessions.stream().map(CallSession::getId).toList();
        Map<Long, List<CallLeg>> legs = groupBySession(legMapper.selectList(new LambdaQueryWrapper<CallLeg>()
            .in(CallLeg::getSessionId, sessionIds)), CallLeg::getSessionId);
        Map<Long, List<CallBridge>> bridges = groupBySession(bridgeMapper.selectList(new LambdaQueryWrapper<CallBridge>()
            .in(CallBridge::getSessionId, sessionIds)), CallBridge::getSessionId);
        Map<Long, List<AgentCallSession>> agents = groupBySession(agentCallSessionMapper.selectList(
            new LambdaQueryWrapper<AgentCallSession>().in(AgentCallSession::getSessionId, sessionIds)), AgentCallSession::getSessionId);
        return sessions.stream()
            .map(session -> toSummary(session, legs.getOrDefault(session.getId(), List.of()),
                bridges.getOrDefault(session.getId(), List.of()), agents.getOrDefault(session.getId(), List.of())))
            .toList();
    }

    @Override
    public DispatchCallTopologyResponse getTopology(String businessCallId) {
        CallSession session = sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .last("limit 1"));
        if (session == null) {
            throw new ServiceException("业务通话不存在");
        }
        List<CallLeg> legs = legMapper.selectList(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getSessionId, session.getId()).orderByAsc(CallLeg::getId));
        List<CallBridge> bridges = bridgeMapper.selectList(new LambdaQueryWrapper<CallBridge>()
            .eq(CallBridge::getSessionId, session.getId()).orderByAsc(CallBridge::getId));
        List<AgentCallSession> agents = agentCallSessionMapper.selectList(new LambdaQueryWrapper<AgentCallSession>()
            .eq(AgentCallSession::getSessionId, session.getId()).orderByAsc(AgentCallSession::getId));

        DispatchCallTopologyResponse response = new DispatchCallTopologyResponse();
        response.setCall(toSummary(session, legs, bridges, agents));
        response.setLegs(legs.stream().map(this::toLeg).toList());
        response.setBridges(bridges.stream().map(this::toBridge).toList());
        response.setAgentSessions(agents.stream().map(this::toAgentSession).toList());
        return response;
    }

    private DispatchActiveCallResponse toSummary(CallSession session, List<CallLeg> legs,
                                                 List<CallBridge> bridges, List<AgentCallSession> agents) {
        List<CallLeg> activeLegs = legs.stream()
            .filter(leg -> Boolean.TRUE.equals(leg.getActive()) && leg.getEndedAt() == null)
            .toList();
        long activeBridgeCount = bridges.stream()
            .filter(bridge -> "BRIDGED".equals(bridge.getBridgeState()) && bridge.getEndedAt() == null)
            .count();
        List<AgentCallSession> visibleAgents = agents.stream()
            .filter(agent -> Boolean.TRUE.equals(agent.getVisible()) && !"ENDED".equals(agent.getSessionState()))
            .toList();
        Set<String> extensions = new LinkedHashSet<>();
        visibleAgents.stream().map(AgentCallSession::getAgentExtension).filter(this::hasText).forEach(extensions::add);
        activeLegs.stream()
            .filter(leg -> "AGENT".equals(leg.getLegRole()) || "CONSULT_AGENT".equals(leg.getLegRole()))
            .map(CallLeg::getAgentExtension).filter(this::hasText).forEach(extensions::add);

        DispatchActiveCallResponse response = new DispatchActiveCallResponse();
        response.setSessionId(session.getId());
        response.setBusinessCallId(session.getBusinessCallId());
        response.setNodeId(session.getNodeId());
        response.setDirection(session.getDirection());
        response.setCallerNumber(session.getCallerNumber());
        response.setCalledNumber(session.getCalledNumber());
        response.setCallStatus(session.getCallStatus());
        response.setCurrentBridgeState(session.getCurrentBridgeState());
        response.setQueueId(session.getHandlingQueueId());
        response.setQueueName(session.getHandlingQueueName());
        response.setOwnerAgentId(session.getOwnerAgentId());
        response.setOwnerAgentExtension(session.getOwnerAgentExtension());
        response.setStartedAt(session.getStartedAt());
        response.setAnsweredAt(session.getAnsweredAt());
        response.setElapsedSeconds(secondsSince(session.getStartedAt()));
        response.setActiveLegCount(activeLegs.size());
        response.setActiveBridgeCount((int) activeBridgeCount);
        response.setVisibleAgentCount(visibleAgents.size());
        response.setAgentExtensions(new ArrayList<>(extensions));
        applyTopologyStatus(response, session, activeLegs);
        return response;
    }

    private void applyTopologyStatus(DispatchActiveCallResponse response, CallSession session, List<CallLeg> activeLegs) {
        if (!activeLegs.isEmpty()) {
            response.setTopologyStatus("NORMAL");
            response.setTopologyMessage("实时电话腿正常");
            return;
        }
        if (secondsSince(session.getStartedAt()) <= 15) {
            response.setTopologyStatus("SYNCING");
            response.setTopologyMessage("通话刚建立，等待电话腿事件同步");
            return;
        }
        response.setTopologyStatus("STALE");
        response.setTopologyMessage("业务通话未结束，但没有活动电话腿，请检查 ESL 事件或残留状态");
    }

    private CallDiagnosticLegResponse toLeg(CallLeg leg) {
        CallDiagnosticLegResponse response = new CallDiagnosticLegResponse();
        response.setId(leg.getId());
        response.setBusinessCallId(leg.getBusinessCallId());
        response.setNodeId(leg.getNodeId());
        response.setLegUuid(leg.getLegUuid());
        response.setLegRole(leg.getLegRole());
        response.setAgentId(leg.getAgentId());
        response.setAgentExtension(leg.getAgentExtension());
        response.setCallerNumber(leg.getCallerNumber());
        response.setCalledNumber(leg.getCalledNumber());
        response.setLegState(leg.getLegState());
        response.setActive(leg.getActive());
        response.setRingingAt(leg.getRingingAt());
        response.setAnsweredAt(leg.getAnsweredAt());
        response.setBridgedAt(leg.getBridgedAt());
        response.setHeldAt(leg.getHeldAt());
        response.setParkedAt(leg.getParkedAt());
        response.setEndedAt(leg.getEndedAt());
        response.setHangupCause(leg.getHangupCause());
        return response;
    }

    private CallDiagnosticBridgeResponse toBridge(CallBridge bridge) {
        CallDiagnosticBridgeResponse response = new CallDiagnosticBridgeResponse();
        response.setId(bridge.getId());
        response.setBusinessCallId(bridge.getBusinessCallId());
        response.setNodeId(bridge.getNodeId());
        response.setLeftLegUuid(bridge.getLeftLegUuid());
        response.setRightLegUuid(bridge.getRightLegUuid());
        response.setBridgeType(bridge.getBridgeType());
        response.setBridgeState(bridge.getBridgeState());
        response.setStartedAt(bridge.getStartedAt());
        response.setEndedAt(bridge.getEndedAt());
        return response;
    }

    private AgentCallSessionResponse toAgentSession(AgentCallSession session) {
        AgentCallSessionResponse response = new AgentCallSessionResponse();
        response.setId(session.getId());
        response.setBusinessCallId(session.getBusinessCallId());
        response.setNodeId(session.getNodeId());
        response.setAgentId(session.getAgentId());
        response.setAgentExtension(session.getAgentExtension());
        response.setAgentLegUuid(session.getAgentLegUuid());
        response.setRole(session.getRole());
        response.setSessionState(session.getSessionState());
        response.setVisible(session.getVisible());
        response.setJoinedAt(session.getJoinedAt());
        response.setLeftAt(session.getLeftAt());
        return response;
    }

    private <T> Map<Long, List<T>> groupBySession(List<T> values, Function<T, Long> sessionIdGetter) {
        return values.stream().collect(Collectors.groupingBy(sessionIdGetter));
    }

    private int secondsSince(LocalDateTime startedAt) {
        if (startedAt == null) {
            return 0;
        }
        return (int) Math.max(0, Duration.between(startedAt, LocalDateTime.now()).getSeconds());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Map<Long, Set<String>> loadRegistrations(Set<Long> nodeIds) {
        Map<Long, Set<String>> result = new HashMap<>();
        for (Long nodeId : nodeIds) {
            try {
                FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
                EslEndpoint endpoint = new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
                result.put(nodeId, commandGateway.listRegisteredExtensions(endpoint));
            } catch (Exception exception) {
                log.warn("调度台读取 FreeSWITCH 分机注册状态失败，nodeId={}，error={}", nodeId, exception.getMessage());
            }
        }
        return result;
    }

    private String extensionKey(Long nodeId, String extension) {
        return String.valueOf(nodeId) + ":" + extension;
    }

    private CallLeg preferActiveLeg(CallLeg left, CallLeg right) {
        return callStatePriority(right) > callStatePriority(left) ? right : left;
    }

    private int callStatePriority(CallLeg leg) {
        if (leg == null) return 0;
        if ("HELD".equals(leg.getLegState())) return 3;
        if (leg.getBridgedAt() != null || "BRIDGED".equals(leg.getLegState())) return 2;
        return 1;
    }

    private String resolveCallStatus(CallLeg leg) {
        if (leg == null) return "IDLE";
        if ("HELD".equals(leg.getLegState())) return "HELD";
        if (leg.getBridgedAt() != null || "BRIDGED".equals(leg.getLegState()) || "ANSWERED".equals(leg.getLegState())) {
            return "TALKING";
        }
        return "RINGING";
    }
}
