package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.service.CallCenterResourceQueryService;
import org.dromara.ai.service.AiGeneratedMediaQueryService;
import org.dromara.ai.service.impl.AiSpeechApplicationServiceImpl;
import org.dromara.call.domain.CallEvent;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.mapper.CallEventMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 队列坐席接通后的附加动作执行器。
 *
 * <p>该执行器只处理接通后的提示动作，不改变队列分配、桥接和挂断流程。
 * 所有 ESL 命令必须使用真实 FreeSWITCH 通话腿 UUID，不能使用业务通话 ID 替代。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueueAnswerActionExecutor {
    private static final String ACTION_NONE = "NONE";
    private static final String ACTION_PLAY_AGENT_NUMBER = "PLAY_AGENT_NUMBER";
    private static final String ACTION_PLAY_MEDIA = "PLAY_MEDIA";

    private final CallCenterResourceQueryService resourceQueryService;
    private final CallSessionMapper sessionMapper;
    private final CallEventMapper eventMapper;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway telephonyCommandGateway;
    private final AiGeneratedMediaQueryService generatedMediaQueryService;

    public void executeAfterAgentAnswer(Long sessionId, CallLeg customerLeg, CallLeg agentLeg,
                                        Long queueId, String queueName) {
        if (sessionId == null || customerLeg == null || agentLeg == null || queueId == null) {
            return;
        }
        CallSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getNodeId() == null) {
            log.warn("队列接通动作跳过，未找到通话会话或节点，sessionId={}，queueId={}", sessionId, queueId);
            return;
        }
        CallCenterResourceQueryService.QueueInfo queue = resourceQueryService.findQueueById(queueId, session.getNodeId());
        String action = normalizeAction(queue == null ? null : queue.answerAction());
        if (ACTION_NONE.equals(action)) {
            return;
        }
        if (!"CUSTOMER".equals(customerLeg.getLegRole()) || StringUtils.isBlank(customerLeg.getLegUuid())) {
            log.warn("队列接通动作拒绝执行，桥接客户腿角色或UUID无效，sessionId={}，businessCallId={}，queueId={}，legRole={}，legUuid={}",
                sessionId, session.getBusinessCallId(), queueId, customerLeg.getLegRole(), customerLeg.getLegUuid());
            return;
        }
        if (!"AGENT".equals(agentLeg.getLegRole()) || StringUtils.isBlank(agentLeg.getLegUuid())) {
            log.warn("队列接通动作拒绝执行，桥接坐席腿角色或UUID无效，sessionId={}，businessCallId={}，queueId={}，legRole={}，legUuid={}",
                sessionId, session.getBusinessCallId(), queueId, agentLeg.getLegRole(), agentLeg.getLegUuid());
            return;
        }
        if (!sessionId.equals(customerLeg.getSessionId()) || !sessionId.equals(agentLeg.getSessionId())) {
            log.warn("队列接通动作拒绝执行，桥接两腿不属于当前业务通话，sessionId={}，customerSessionId={}，agentSessionId={}，customerLegUuid={}，agentLegUuid={}",
                sessionId, customerLeg.getSessionId(), agentLeg.getSessionId(), customerLeg.getLegUuid(), agentLeg.getLegUuid());
            return;
        }
        boolean alreadyExecutedForAgentLeg = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .eq(CallEvent::getEventType, "QUEUE_ANSWER_ACTION")
            .eq(CallEvent::getRelatedChannelUuid, agentLeg.getLegUuid()));
        if (alreadyExecutedForAgentLeg) {
            log.debug("队列接通动作跳过，本次坐席腿已执行，sessionId={}，businessCallId={}，queueId={}，customerLegUuid={}，agentLegUuid={}",
                sessionId, session.getBusinessCallId(), queueId, customerLeg.getLegUuid(), agentLeg.getLegUuid());
            return;
        }

        EslEndpoint endpoint = endpoint(session.getNodeId());
        try {
            executeAction(endpoint, session.getNodeId(), customerLeg.getLegUuid(), agentLeg, queue, action);
            appendActionEvent(session, customerLeg, agentLeg, queue, queueName, action, "SUCCESS", null);
            log.info("队列接通动作已在桥接后执行，sessionId={}，businessCallId={}，queueId={}，action={}，customerLegUuid={}，agentLegUuid={}，agentExtension={}",
                sessionId, session.getBusinessCallId(), queueId, action, customerLeg.getLegUuid(),
                agentLeg.getLegUuid(), agentLeg.getAgentExtension());
        } catch (Exception exception) {
            appendActionEvent(session, customerLeg, agentLeg, queue, queueName, action, "FAILED", exception.getMessage());
            log.warn("队列接通动作执行失败，不影响当前通话，sessionId={}，businessCallId={}，queueId={}，action={}，customerLegUuid={}，agentLegUuid={}，error={}",
                sessionId, session.getBusinessCallId(), queueId, action, customerLeg.getLegUuid(),
                agentLeg.getLegUuid(), exception.getMessage());
        }
    }

    private void executeAction(EslEndpoint endpoint, Long nodeId, String customerLegUuid, CallLeg agentLeg,
                               CallCenterResourceQueryService.QueueInfo queue, String action) {
        if (ACTION_PLAY_MEDIA.equals(action)) {
            if (queue == null || StringUtils.isBlank(queue.answerMediaPath())) {
                throw new IllegalStateException("队列接通提示音尚未同步到当前 FreeSWITCH 节点");
            }
            telephonyCommandGateway.broadcastPlayback(endpoint, customerLegUuid, queue.answerMediaPath(), "aleg");
            return;
        }
        if (ACTION_PLAY_AGENT_NUMBER.equals(action)) {
            String number = agentLeg.getAgentExtension();
            if (StringUtils.isBlank(number)) {
                throw new IllegalStateException("坐席分机为空，无法播放工号");
            }
            if (agentLeg.getAgentId() == null) {
                throw new IllegalStateException("坐席ID为空，无法查询工号提示音");
            }
            String promptPath = generatedMediaQueryService.findSyncedPath(
                AiSpeechApplicationServiceImpl.BUSINESS_AGENT_NUMBER_PROMPT,
                agentLeg.getAgentId(),
                nodeId
            );
            if (StringUtils.isBlank(promptPath)) {
                throw new IllegalStateException("坐席工号提示音尚未生成或未同步到当前 FreeSWITCH 节点");
            }
            telephonyCommandGateway.broadcastPlayback(endpoint, customerLegUuid, promptPath, "aleg");
        }
    }

    private String normalizeAction(String action) {
        if (StringUtils.isBlank(action)) {
            return ACTION_NONE;
        }
        return action.trim().toUpperCase();
    }

    private void appendActionEvent(CallSession session, CallLeg customerLeg, CallLeg agentLeg,
                                   CallCenterResourceQueryService.QueueInfo queue, String queueName,
                                   String action, String status, String failureReason) {
        CallEvent event = new CallEvent();
        event.setSessionId(session.getId());
        event.setChannelUuid(customerLeg.getLegUuid());
        event.setRelatedChannelUuid(agentLeg.getLegUuid());
        event.setEventType("QUEUE_ANSWER_ACTION");
        event.setFromTarget(queueName != null ? queueName : queue == null ? null : queue.queueName());
        event.setToTarget(agentLeg.getAgentExtension());
        event.setOccurredAt(LocalDateTime.now());
        event.setMetadataJson(actionMetadata(session, customerLeg, agentLeg, queue, action, status, failureReason));
        eventMapper.insert(event);
    }

    private String actionMetadata(CallSession session, CallLeg customerLeg, CallLeg agentLeg,
                                  CallCenterResourceQueryService.QueueInfo queue, String action,
                                  String status, String failureReason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "agent_answer");
        metadata.put("status", status);
        metadata.put("action", action);
        metadata.put("businessCallId", session.getBusinessCallId());
        metadata.put("nodeId", session.getNodeId());
        metadata.put("queueId", queue == null ? null : queue.queueId());
        metadata.put("queueCode", queue == null ? null : queue.queueCode());
        metadata.put("agentId", agentLeg.getAgentId());
        metadata.put("agentExtension", agentLeg.getAgentExtension());
        metadata.put("customerLegUuid", customerLeg.getLegUuid());
        metadata.put("agentLegUuid", agentLeg.getLegUuid());
        metadata.put("answerMediaId", queue == null ? null : queue.answerMediaId());
        metadata.put("answerMediaPath", queue == null ? null : queue.answerMediaPath());
        if (StringUtils.isNotBlank(failureReason)) {
            metadata.put("failureReason", failureReason);
        }
        return JsonUtils.toJsonString(metadata);
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

}
