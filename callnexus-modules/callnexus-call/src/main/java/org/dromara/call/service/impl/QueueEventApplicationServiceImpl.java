package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.service.CallCenterResourceQueryService;
import org.dromara.agent.service.HandlingQueueResolver;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.CallEvent;
import org.dromara.call.domain.CallRecord;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.mapper.CallEventMapper;
import org.dromara.call.mapper.CallRecordMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.QueueEventApplicationService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.event.queue.AgentRingSignalEvent;
import org.dromara.resource.event.queue.QueueEntrySignalEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 队列事件轨迹处理实现。
 *
 * <p>由于 FreeSWITCH 1.10.x 的 mod_callcenter 不通过 ESL CUSTOM 事件广播队列生命周期，
 * 本实现同时支持两条事件来源：
 * <ol>
 *   <li>directory/dialplan xml-curl 信号（推荐，稳定）：通过 Spring 事件消费
 *       {@link QueueEntrySignalEvent}（进入队列）和 {@link AgentRingSignalEvent}（坐席振铃）。</li>
 *   <li>ESL CUSTOM 队列事件（备用，未来 FS 升级可能恢复）：通过 {@link #handleQueueEvent(TelephonyEvent)}。</li>
 * </ol>
 * 两条路径共用 {@link #appendQueueTimelineEvent} 公共落库方法。
 *
 * <p>坐席接听（AGENT_ANSWER）由 ESL CHANNEL_BRIDGE 触发（见
 * {@link TelephonyEventHandlerImpl}），因为该信号比 ESL 事件更可靠。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueueEventApplicationServiceImpl implements QueueEventApplicationService, HandlingQueueResolver {
    private final CallRecordMapper recordMapper;
    private final CallSessionMapper sessionMapper;
    private final CallEventMapper eventMapper;
    private final CallCenterResourceQueryService resourceQueryService;

    // ==================== Spring 事件消费（directory/dialplan 信号，推荐路径） ====================

    /**
     * 消费"进入队列"信号：dialplan 命中 QUEUE 路由时触发。
     * 在通话时间线落 QUEUE_IN 节点。
     *
     * <p>注意：同步执行（项目未启用 @EnableAsync）。发布发生在 dialplan xml-curl 的 HTTP 请求线程，
     * 落库是单条 INSERT，耗时可控；落库异常会被 catch，不影响 dialplan 响应。
     */
    @EventListener
    public void onQueueEntry(QueueEntrySignalEvent event) {
        if (StringUtils.isBlank(event.businessCallId())) {
            return;
        }
        try {
            TenantHelper.dynamic(event.tenantId(), () -> persistQueueEntry(event));
        } catch (Exception exception) {
            log.error("处理进入队列信号事件失败，businessCallId={}，queueCode={}",
                event.businessCallId(), event.queueCode(), exception);
        }
    }

    /**
     * 消费"坐席振铃"信号：mod_callcenter 通过 directory 查询坐席时触发。
     * 在通话时间线落 AGENT_RING 节点。
     */
    @EventListener
    public void onAgentRing(AgentRingSignalEvent event) {
        if (StringUtils.isBlank(event.memberSessionUuid())) {
            return;
        }
        try {
            TenantHelper.dynamic(event.tenantId(), () -> persistAgentRing(event));
        } catch (Exception exception) {
            log.error("处理坐席振铃信号事件失败，memberSessionUuid={}，queueCode={}",
                event.memberSessionUuid(), event.queueCode(), exception);
        }
    }

    private void persistQueueEntry(QueueEntrySignalEvent event) {
        Long sessionId = resolveSessionIdByChannelUuid(event.channelUuid());
        if (sessionId == null) {
            // dialplan 请求早于 CHANNEL_CREATE 落库时可能查不到，第一版仅记录。
            log.warn("进入队列信号未找到对应业务通话腿，已跳过，businessCallId={}，channelUuid={}，queueCode={}",
                event.businessCallId(), event.channelUuid(), event.queueCode());
            return;
        }
        appendQueueTimelineEvent(
            sessionId,
            event.channelUuid(),
            null,
            "QUEUE_IN",
            event.queueName() != null ? event.queueName() + "（" + event.queueCode() + "）" : event.queueCode(),
            null,
            buildQueueEntryMetadata(event)
        );
        log.info("已落库进入队列事件，sessionId={}，queueId={}，queueName={}",
            sessionId, event.queueId(), event.queueName());
    }

    private void persistAgentRing(AgentRingSignalEvent event) {
        Long sessionId = resolveSessionIdByChannelUuid(event.memberSessionUuid());
        if (sessionId == null) {
            log.warn("坐席振铃信号未找到对应业务通话腿，已跳过，memberSessionUuid={}，queueCode={}",
                event.memberSessionUuid(), event.queueCode());
            return;
        }
        // 反查队列信息，拿到队列名用于展示；nodeId 可能为 null（directory 请求未带节点标识），按 domain 兜底。
        CallCenterResourceQueryService.QueueInfo queue = resolveQueueFromAgentRingEvent(event);
        Long agentId = resolveAgentId(event.agentIdentity(), event.nodeId());
        String queueLabel = queue != null ? queue.queueName() : event.queueCode();
        String agentLabel = formatAgentLabel(event.agentIdentity(), agentId);

        // 兼容 IVR 转队列场景：号码路由是 IVR，QueueDialplanRouteHandler 不会触发，
        // 但 directory user_call 带 cc_queue 已证明呼叫进入了队列。
        // 此时如果 session 还没有 QUEUE_IN 事件，由坐席振铃信号补落 QUEUE_IN。
        boolean hasQueueIn = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .eq(CallEvent::getEventType, "QUEUE_IN"));
        if (!hasQueueIn && queue != null) {
            appendQueueTimelineEvent(
                sessionId,
                event.memberSessionUuid(),
                null,
                "QUEUE_IN",
                queue.queueName() != null ? queue.queueName() + "（" + queue.queueCode() + "）" : event.queueCode(),
                null,
                buildQueueEntryMetadataFromAgentRing(event, queue)
            );
            log.info("由坐席振铃信号补充进入队列事件（IVR转队列场景），sessionId={}，queueCode={}，queueName={}",
                sessionId, event.queueCode(), queue.queueName());
        }

        appendQueueTimelineEvent(
            sessionId,
            event.memberSessionUuid(),
            event.agentIdentity(),
            "AGENT_RING",
            queueLabel,
            agentLabel,
            buildAgentRingMetadata(event, queue, agentId)
        );
        log.info("已落库坐席振铃事件，sessionId={}，queueCode={}，agentIdentity={}，agentId={}",
            sessionId, event.queueCode(), event.agentIdentity(), agentId);
    }

    // ==================== ESL 路径（备用，未来 FS 升级可能恢复） ====================

    @Override
    public void handleQueueEvent(TelephonyEvent event) {
        // 保留 ESL CUSTOM 路径作为备用。当前 FreeSWITCH 1.10.x 不发这些事件，实际不会进入。
        String callerUuid = event.uuid();
        if (StringUtils.isBlank(callerUuid)) {
            return;
        }
        Long sessionId = resolveSessionIdByChannelUuid(callerUuid);
        if (sessionId == null) {
            return;
        }
        String eventType = mapEslSubclassToEventType(event.eventSubclass());
        if (eventType == null) {
            return;
        }
        appendQueueTimelineEvent(sessionId, callerUuid, event.headers().get(EslHeaders.CC_AGENT),
            eventType, event.headers().get(EslHeaders.CC_QUEUE), null, JsonUtils.toJsonString(event.headers()));
    }

    // ==================== ESL CHANNEL_BRIDGE 路径：记录坐席接听 ====================

    /**
     * 当 ESL CHANNEL_BRIDGE 事件发生且关联的业务通话来自队列时，记录"坐席接听"节点，
     * 并把实际接听队列写入业务通话主记录，供话后整理时长计算和详情展示。
     *
     * <p>由 {@link TelephonyEventHandlerImpl} 在 BRIDGE 时调用。
     *
     * @param channelUuid 桥接坐席腿的 channel uuid
     * @return 实际接听队列 ID（用于后续话后整理），无队列来电返回 null
     */
    @Override
    public Long recordAgentAnswerOnBridge(String channelUuid) {
        if (StringUtils.isBlank(channelUuid)) return null;
        CallRecord leg = recordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
            .and(wrapper -> wrapper.eq(CallRecord::getChannelUuid, channelUuid).or().eq(CallRecord::getCallUuid, channelUuid))
            .last("limit 1"));
        if (leg == null || leg.getSessionId() == null) return null;
        Long sessionId = leg.getSessionId();

        // 只有队列来电才记录接听队列。判断依据：该 session 已有 QUEUE_IN 时间线节点。
        boolean hasQueueIn = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .eq(CallEvent::getEventType, "QUEUE_IN"));
        if (!hasQueueIn) return null;

        // 队列信息从已落库的 QUEUE_IN 事件的 metadata 反查；若无则用 session 上的 handlingQueueId 兜底。
        QueueEntryInfo entry = readQueueEntryFromTimeline(sessionId);
        Long queueId = entry != null ? entry.queueId() : null;
        String queueName = entry != null ? entry.queueName() : null;
        String queueCode = entry != null ? entry.queueCode() : null;

        appendQueueTimelineEvent(
            sessionId,
            channelUuid,
            leg.getAgentExtension() != null ? leg.getAgentExtension() + "@" + leg.getTenantId() : null,
            "AGENT_ANSWER",
            queueName != null ? queueName : queueCode,
            formatAgentLabel(leg.getAgentExtension(), leg.getAgentId()),
            buildAgentAnswerMetadata(leg, queueId, queueName)
        );

        if (queueId != null) {
            sessionMapper.update(null, new LambdaUpdateWrapper<CallSession>()
                .eq(CallSession::getId, sessionId)
                .set(CallSession::getHandlingQueueId, queueId)
                .set(CallSession::getHandlingQueueName, queueName));
            log.info("已记录本次通话实际接听队列，sessionId={}，queueId={}，queueName={}",
                sessionId, queueId, queueName);
        }
        return queueId;
    }

    // ==================== 业务通话聚合结束：推断队列超时/放弃 ====================

    @Override
    public void recordQueueTerminationIfUnanswered(Long sessionId, String channelUuid, String hangupCause) {
        if (sessionId == null) return;
        boolean hasQueueIn = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .eq(CallEvent::getEventType, "QUEUE_IN"));
        if (!hasQueueIn) return;
        boolean hasAgentAnswer = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .eq(CallEvent::getEventType, "AGENT_ANSWER"));
        if (hasAgentAnswer) return;
        // 主叫主动放弃 vs 队列超时：ORIGINATOR_CANCEL 表示主叫侧挂断，其余视为队列等待超时。
        String eventType = "ORIGINATOR_CANCEL".equalsIgnoreCase(hangupCause) ? "ABANDON" : "QUEUE_TIMEOUT";
        appendQueueTimelineEvent(sessionId, channelUuid, null, eventType, null, hangupCause,
            buildTerminationMetadata(eventType, hangupCause));
        log.info("已落库队列未接听终止事件，sessionId={}，eventType={}，hangupCause={}",
            sessionId, eventType, hangupCause);
    }

    private String buildTerminationMetadata(String eventType, String hangupCause) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "session_aggregate");
        metadata.put("eventType", eventType);
        metadata.put("hangupCause", hangupCause);
        return JsonUtils.toJsonString(metadata);
    }

    // ==================== HandlingQueueResolver 契约（话后整理时长查询） ====================

    @Override
    public Integer resolveWrapUpSeconds(String channelUuid) {
        if (StringUtils.isBlank(channelUuid)) return null;
        CallRecord leg = recordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
            .and(wrapper -> wrapper.eq(CallRecord::getChannelUuid, channelUuid).or().eq(CallRecord::getCallUuid, channelUuid))
            .last("limit 1"));
        if (leg == null || leg.getSessionId() == null) return null;
        CallSession session = sessionMapper.selectById(leg.getSessionId());
        if (session == null || session.getHandlingQueueId() == null) return null;
        CallCenterResourceQueryService.QueueInfo queue = resourceQueryService.findQueueById(session.getHandlingQueueId());
        return queue == null ? null : queue.wrapUpSeconds();
    }

    // ==================== 公共落库方法 ====================

    /**
     * 把队列生命周期事件写入 cc_call_event 时间线。
     * 对单次发生事件（QUEUE_IN/AGENT_ANSWER）按 session+eventType 去重，避免重复落库。
     */
    private void appendQueueTimelineEvent(Long sessionId, String channelUuid, String relatedChannelUuid,
                                          String eventType, String fromTarget, String toTarget, String metadataJson) {
        if (isSingleOccurrenceEvent(eventType)) {
            boolean exists = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
                .eq(CallEvent::getSessionId, sessionId)
                .eq(CallEvent::getEventType, eventType));
            if (exists) {
                log.info("队列时间线事件已存在，跳过重复落库，sessionId={}，eventType={}", sessionId, eventType);
                return;
            }
        }
        CallEvent timelineEvent = new CallEvent();
        timelineEvent.setSessionId(sessionId);
        timelineEvent.setChannelUuid(channelUuid);
        timelineEvent.setRelatedChannelUuid(relatedChannelUuid);
        timelineEvent.setEventType(eventType);
        timelineEvent.setFromTarget(fromTarget);
        timelineEvent.setToTarget(toTarget);
        timelineEvent.setOccurredAt(LocalDateTime.now());
        timelineEvent.setMetadataJson(metadataJson);
        eventMapper.insert(timelineEvent);
    }

    private boolean isSingleOccurrenceEvent(String eventType) {
        return "QUEUE_IN".equals(eventType) || "AGENT_ANSWER".equals(eventType);
    }

    // ==================== 辅助方法 ====================

    private Long resolveSessionIdByChannelUuid(String channelUuid) {
        if (StringUtils.isBlank(channelUuid)) return null;
        CallRecord leg = recordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
            .and(wrapper -> wrapper.eq(CallRecord::getChannelUuid, channelUuid).or().eq(CallRecord::getCallUuid, channelUuid))
            .last("limit 1"));
        return leg == null ? null : leg.getSessionId();
    }

    private CallCenterResourceQueryService.QueueInfo resolveQueueFromAgentRingEvent(AgentRingSignalEvent event) {
        if (event.nodeId() != null) {
            return resourceQueryService.findQueueByCode(event.queueCode(), event.nodeId());
        }
        // nodeId 解析失败时，按队列编码兜底查（不校验节点组），保证时间线能落库。
        return null;
    }

    private Long resolveAgentId(String agentIdentity, Long nodeId) {
        if (StringUtils.isBlank(agentIdentity) || nodeId == null) return null;
        return resourceQueryService.findAgentIdByIdentity(agentIdentity, nodeId);
    }

    private String formatAgentLabel(String agentIdentity, Long agentId) {
        if (StringUtils.isBlank(agentIdentity)) return null;
        String extension = agentIdentity.contains("@") ? agentIdentity.substring(0, agentIdentity.indexOf('@')) : agentIdentity;
        return agentId == null ? extension : extension + "(坐席" + agentId + ")";
    }

    /**
     * 从已落库的 QUEUE_IN 时间线节点的 metadataJson 反查队列信息。
     */
    private QueueEntryInfo readQueueEntryFromTimeline(Long sessionId) {
        CallEvent queueInEvent = eventMapper.selectOne(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .eq(CallEvent::getEventType, "QUEUE_IN")
            .orderByDesc(CallEvent::getOccurredAt)
            .last("limit 1"));
        if (queueInEvent == null || StringUtils.isBlank(queueInEvent.getMetadataJson())) return null;
        try {
            Map<String, Object> metadata = JsonUtils.parseMap(queueInEvent.getMetadataJson());
            Long queueId = metadata.get("queueId") == null ? null : Long.valueOf(metadata.get("queueId").toString());
            String queueCode = metadata.get("queueCode") == null ? null : metadata.get("queueCode").toString();
            String queueName = metadata.get("queueName") == null ? null : metadata.get("queueName").toString();
            return new QueueEntryInfo(queueId, queueCode, queueName);
        } catch (Exception exception) {
            log.warn("解析 QUEUE_IN 事件 metadata 失败，sessionId={}", sessionId, exception);
            return null;
        }
    }

    private String mapEslSubclassToEventType(String subclass) {
        if (subclass == null) return null;
        return switch (subclass) {
            case EslEventNames.SUBCLASS_CC_COMING -> "QUEUE_IN";
            case EslEventNames.SUBCLASS_CC_QUEUE -> "QUEUE_WAIT";
            case EslEventNames.SUBCLASS_CC_RING_AGENT -> "AGENT_RING";
            case EslEventNames.SUBCLASS_CC_AGENT_ANSWER -> "AGENT_ANSWER";
            case EslEventNames.SUBCLASS_CC_TIMEOUT -> "QUEUE_TIMEOUT";
            case EslEventNames.SUBCLASS_CC_ABANDON -> "ABANDON";
            case EslEventNames.SUBCLASS_CC_NO_ANSWER, EslEventNames.SUBCLASS_CC_REJECTED -> "AGENT_NO_ANSWER";
            default -> null;
        };
    }

    private String buildQueueEntryMetadata(QueueEntrySignalEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "dialplan");
        metadata.put("queueId", event.queueId());
        metadata.put("queueCode", event.queueCode());
        metadata.put("queueName", event.queueName());
        metadata.put("nodeId", event.nodeId());
        return JsonUtils.toJsonString(metadata);
    }

    /**
     * IVR 转队列场景下，由坐席振铃信号补充 QUEUE_IN 事件时使用的 metadata 构建方法。
     */
    private String buildQueueEntryMetadataFromAgentRing(AgentRingSignalEvent event, CallCenterResourceQueryService.QueueInfo queue) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "agent_ring_supplement");
        metadata.put("queueId", queue.queueId());
        metadata.put("queueCode", queue.queueCode());
        metadata.put("queueName", queue.queueName());
        metadata.put("nodeId", event.nodeId());
        return JsonUtils.toJsonString(metadata);
    }

    private String buildAgentRingMetadata(AgentRingSignalEvent event, CallCenterResourceQueryService.QueueInfo queue, Long agentId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "directory");
        metadata.put("ccQueue", event.queueCode());
        metadata.put("ccAgent", event.agentIdentity());
        metadata.put("action", event.action());
        metadata.put("nodeId", event.nodeId());
        if (queue != null) metadata.put("queueId", queue.queueId());
        if (agentId != null) metadata.put("agentId", agentId);
        return JsonUtils.toJsonString(metadata);
    }

    private String buildAgentAnswerMetadata(CallRecord leg, Long queueId, String queueName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "channel_bridge");
        metadata.put("agentId", leg.getAgentId());
        metadata.put("agentExtension", leg.getAgentExtension());
        metadata.put("queueId", queueId);
        metadata.put("queueName", queueName);
        return JsonUtils.toJsonString(metadata);
    }

    private record QueueEntryInfo(Long queueId, String queueCode, String queueName) {
    }
}
