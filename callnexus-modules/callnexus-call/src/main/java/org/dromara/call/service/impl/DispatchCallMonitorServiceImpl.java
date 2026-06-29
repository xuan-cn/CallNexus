package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.AgentCallSession;
import org.dromara.call.domain.CallBridge;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.response.AgentCallSessionResponse;
import org.dromara.call.domain.response.CallDiagnosticBridgeResponse;
import org.dromara.call.domain.response.CallDiagnosticLegResponse;
import org.dromara.call.domain.response.DispatchActiveCallResponse;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;
import org.dromara.call.mapper.AgentCallSessionMapper;
import org.dromara.call.mapper.CallBridgeMapper;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.common.core.exception.ServiceException;
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

@Service
@RequiredArgsConstructor
public class DispatchCallMonitorServiceImpl implements DispatchCallMonitorService {
    private static final int MAX_ACTIVE_CALLS = 500;

    private final CallSessionMapper sessionMapper;
    private final CallLegMapper legMapper;
    private final CallBridgeMapper bridgeMapper;
    private final AgentCallSessionMapper agentCallSessionMapper;

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
        activeLegs.stream().map(CallLeg::getAgentExtension).filter(this::hasText).forEach(extensions::add);

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
}
