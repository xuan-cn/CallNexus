package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.service.CallCenterResourceQueryService;
import org.dromara.agent.service.HandlingQueueResolver;
import org.dromara.agent.service.StickyAgentRegistry;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.CallEvent;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallRecord;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.mapper.CallEventMapper;
import org.dromara.call.mapper.CallLegMapper;
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

import java.time.Duration;
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
    private final CallLegMapper legMapper;
    private final CallSessionMapper sessionMapper;
    private final CallEventMapper eventMapper;
    private final CallCenterResourceQueryService resourceQueryService;
    private final QueueAnswerActionExecutor queueAnswerActionExecutor;
    private final StickyAgentRegistry stickyAgentRegistry;

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

        boolean hasAgentAnswer = eventMapper.exists(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .eq(CallEvent::getEventType, "AGENT_ANSWER"));
        if (hasAgentAnswer) {
            return queueId;
        }

        CallRecord agentLeg = resolveQueueAnswerAgentLeg(sessionId, leg, channelUuid);
        if (agentLeg == null || agentLeg.getAgentId() == null) {
            log.warn("队列坐席接听事件未解析到坐席腿，已跳过接通动作和记忆坐席登记，sessionId={}，bridgeUuid={}，queueId={}",
                sessionId, channelUuid, queueId);
            return queueId;
        }

        appendQueueTimelineEvent(
            sessionId,
            channelUuid,
            agentLeg.getAgentExtension() != null ? agentLeg.getAgentExtension() + "@" + agentLeg.getTenantId() : null,
            "AGENT_ANSWER",
            queueName != null ? queueName : queueCode,
            formatAgentLabel(agentLeg.getAgentExtension(), agentLeg.getAgentId()),
            buildAgentAnswerMetadata(agentLeg, queueId, queueName)
        );

        if (queueId != null) {
            sessionMapper.update(null, new LambdaUpdateWrapper<CallSession>()
                .eq(CallSession::getId, sessionId)
                .set(CallSession::getHandlingQueueId, queueId)
                .set(CallSession::getHandlingQueueName, queueName));
            log.info("已记录本次通话实际接听队列，sessionId={}，queueId={}，queueName={}",
                sessionId, queueId, queueName);
        }
        recordStickyAgentIfEnabled(sessionId, agentLeg, queueId);
        queueAnswerActionExecutor.executeAfterAgentAnswer(sessionId, agentLeg, queueId, queueName);
        return queueId;
    }

    /**
     * CHANNEL_BRIDGE 的事件 UUID 不稳定：有时是客户腿，有时是坐席腿。
     * 队列接听、记忆坐席和接通动作必须使用真正的坐席腿，避免 agentId 为空导致记忆失败。
     */
    private CallRecord resolveQueueAnswerAgentLeg(Long sessionId, CallRecord bridgedLeg, String bridgeUuid) {
        if (bridgedLeg != null && bridgedLeg.getAgentId() != null) {
            return bridgedLeg;
        }
        CallRecord recordLeg = recordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
            .eq(CallRecord::getSessionId, sessionId)
            .isNotNull(CallRecord::getAgentId)
            .isNotNull(CallRecord::getAgentExtension)
            .and(wrapper -> wrapper.ne(CallRecord::getCallStatus, "ENDED").or().isNull(CallRecord::getCallStatus))
            .orderByDesc(CallRecord::getAnsweredAt)
            .orderByDesc(CallRecord::getRingingAt)
            .orderByDesc(CallRecord::getId)
            .last("limit 1"));
        if (recordLeg != null) {
            return recordLeg;
        }
        CallLeg stableLeg = legMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getSessionId, sessionId)
            .eq(CallLeg::getLegRole, "AGENT")
            .isNotNull(CallLeg::getAgentId)
            .isNotNull(CallLeg::getAgentExtension)
            .orderByDesc(CallLeg::getActive)
            .orderByDesc(CallLeg::getAnsweredAt)
            .orderByDesc(CallLeg::getBridgedAt)
            .orderByDesc(CallLeg::getRingingAt)
            .orderByDesc(CallLeg::getId)
            .last("limit 1"));
        if (stableLeg != null) {
            return toCallRecord(stableLeg);
        }
        return resolveAgentLegFromLatestRingEvent(sessionId, bridgeUuid);
    }

    private CallRecord toCallRecord(CallLeg leg) {
        CallRecord record = new CallRecord();
        record.setTenantId(leg.getTenantId());
        record.setSessionId(leg.getSessionId());
        record.setNodeId(leg.getNodeId());
        record.setChannelUuid(leg.getLegUuid());
        record.setCallUuid(leg.getBusinessCallId());
        record.setCallerNumber(leg.getCallerNumber());
        record.setCalledNumber(leg.getCalledNumber());
        record.setAgentId(leg.getAgentId());
        record.setAgentExtension(leg.getAgentExtension());
        record.setCallStatus(leg.getLegState());
        record.setRingingAt(leg.getRingingAt());
        record.setAnsweredAt(leg.getAnsweredAt());
        record.setEndedAt(leg.getEndedAt());
        record.setHangupCause(leg.getHangupCause());
        return record;
    }

    private CallRecord resolveAgentLegFromLatestRingEvent(Long sessionId, String bridgeUuid) {
        CallEvent ringEvent = eventMapper.selectOne(new LambdaQueryWrapper<CallEvent>()
            .eq(CallEvent::getSessionId, sessionId)
            .eq(CallEvent::getEventType, "AGENT_RING")
            .orderByDesc(CallEvent::getOccurredAt)
            .last("limit 1"));
        if (ringEvent == null || StringUtils.isBlank(ringEvent.getMetadataJson())) {
            return null;
        }
        try {
            Map<String, Object> metadata = JsonUtils.parseMap(ringEvent.getMetadataJson());
            Object agentIdValue = metadata.get("agentId");
            Object ccAgentValue = metadata.get("ccAgent");
            if (agentIdValue == null || ccAgentValue == null || StringUtils.isBlank(ccAgentValue.toString())) {
                return null;
            }
            CallSession session = sessionMapper.selectById(sessionId);
            String agentIdentity = ccAgentValue.toString();
            CallRecord record = new CallRecord();
            record.setTenantId(session == null ? null : session.getTenantId());
            record.setSessionId(sessionId);
            record.setNodeId(session == null ? null : session.getNodeId());
            record.setChannelUuid(bridgeUuid);
            record.setCallUuid(session == null ? null : session.getBusinessCallId());
            record.setCallerNumber(session == null ? null : session.getCallerNumber());
            record.setCalledNumber(extensionFromIdentity(agentIdentity));
            record.setAgentId(Long.valueOf(agentIdValue.toString()));
            record.setAgentExtension(extensionFromIdentity(agentIdentity));
            record.setAnsweredAt(ringEvent.getOccurredAt());
            log.info("通过最近坐席振铃事件兜底解析队列接听坐席，sessionId={}，bridgeUuid={}，agentIdentity={}，agentId={}",
                sessionId, bridgeUuid, agentIdentity, record.getAgentId());
            return record;
        } catch (Exception exception) {
            log.warn("通过坐席振铃事件兜底解析队列接听坐席失败，sessionId={}，bridgeUuid={}",
                sessionId, bridgeUuid, exception);
            return null;
        }
    }

    private String extensionFromIdentity(String agentIdentity) {
        if (StringUtils.isBlank(agentIdentity)) {
            return null;
        }
        int domainIndex = agentIdentity.indexOf('@');
        return domainIndex > 0 ? agentIdentity.substring(0, domainIndex) : agentIdentity;
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
        String eventType = resolveUnansweredQueueTerminationType(sessionId, hangupCause);
        appendQueueTimelineEvent(sessionId, channelUuid, null, eventType, null, hangupCause,
            buildTerminationMetadata(eventType, hangupCause));
        log.info("已落库队列未接听终止事件，sessionId={}，eventType={}，hangupCause={}",
            sessionId, eventType, hangupCause);
    }

    private String resolveUnansweredQueueTerminationType(Long sessionId, String hangupCause) {
        if ("ORIGINATOR_CANCEL".equalsIgnoreCase(hangupCause)) {
            return "ABANDON";
        }
        QueueEntryInfo entry = readQueueEntryFromTimeline(sessionId);
        if (entry != null && entry.queueId() != null && entry.occurredAt() != null) {
            CallCenterResourceQueryService.QueueInfo queue = resourceQueryService.findQueueById(entry.queueId());
            Integer maxWaitSeconds = queue == null ? null : queue.maxWaitSeconds();
            if (maxWaitSeconds != null && maxWaitSeconds > 0) {
                LocalDateTime endedAt = LocalDateTime.now();
                CallSession session = sessionMapper.selectById(sessionId);
                if (session != null && session.getEndedAt() != null) {
                    endedAt = session.getEndedAt();
                }
                long waitedSeconds = Math.max(0, Duration.between(entry.occurredAt(), endedAt).getSeconds());
                return waitedSeconds >= maxWaitSeconds ? "QUEUE_TIMEOUT" : "ABANDON";
            }
        }
        return "QUEUE_TIMEOUT";
    }

    private String buildTerminationMetadata(String eventType, String hangupCause) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "session_aggregate");
        metadata.put("eventType", eventType);
        metadata.put("hangupCause", hangupCause);
        return JsonUtils.toJsonString(metadata);
    }

    // ==================== 队列 DTMF 按键采集 ====================

    /**
     * 实现说明：只在队列已接通的通话腿上落 {@code QUEUE_DTMF} 时间线事件，第一版仅做记录，不参与挂机决策。
     * 按 {@code cc_call_leg} 的 {@code leg_role} 判断按键来源是坐席腿还是客户腿，再与队列配置的
     * {@code hangupKeyAction} 校验是否需要落库。
     */
    @Override
    public void recordQueueDtmfIfApplicable(String channelUuid, String digit, String source) {
        if (StringUtils.isBlank(channelUuid) || StringUtils.isBlank(digit)) return;
        CallRecord leg = recordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
            .and(wrapper -> wrapper.eq(CallRecord::getChannelUuid, channelUuid).or().eq(CallRecord::getCallUuid, channelUuid))
            .last("limit 1"));
        if (leg == null || leg.getSessionId() == null) return;
        if (StringUtils.isBlank(leg.getTenantId())) {
            log.warn("队列 DTMF 按键所在通话腿缺少租户标识，跳过落库，channelUuid={}，digit={}", channelUuid, digit);
            return;
        }
        Long sessionId = leg.getSessionId();

        CallSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getHandlingQueueId() == null) return;

        CallCenterResourceQueryService.QueueInfo queue = resourceQueryService.findQueueById(session.getHandlingQueueId());
        String collectMode = queue == null ? "NONE" : safeCollectMode(queue.hangupKeyAction());
        if ("NONE".equals(collectMode)) return;

        String legSource = leg.getAgentExtension() != null ? "AGENT" : "CUSTOMER";
        if ("AGENT".equals(collectMode) && !"AGENT".equals(legSource)) return;
        if ("CALLER".equals(collectMode) && !"CUSTOMER".equals(legSource)) return;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "channel_dtmf");
        metadata.put("dtmfDigit", digit);
        metadata.put("dtmfSource", source);
        metadata.put("collectMode", collectMode);
        metadata.put("legRole", legSource);
        metadata.put("agentId", leg.getAgentId());
        metadata.put("agentExtension", leg.getAgentExtension());
        metadata.put("queueId", session.getHandlingQueueId());
        metadata.put("queueName", session.getHandlingQueueName());

        TenantHelper.dynamic(leg.getTenantId(), () -> appendQueueTimelineEvent(
            sessionId,
            channelUuid,
            null,
            "QUEUE_DTMF",
            "AGENT".equals(legSource) ? leg.getAgentExtension() : session.getCallerNumber(),
            digit,
            JsonUtils.toJsonString(metadata)
        ));
        log.info("已记录队列通话 DTMF 按键，sessionId={}，queueId={}，legRole={}，digit={}，source={}",
            sessionId, session.getHandlingQueueId(), legSource, digit, source);
    }

    private String safeCollectMode(String action) {
        if (StringUtils.isBlank(action)) return "NONE";
        String normalized = action.trim().toUpperCase();
        return switch (normalized) {
            case "AGENT", "CALLER", "NONE" -> normalized;
            default -> "NONE";
        };
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
            return new QueueEntryInfo(queueId, queueCode, queueName, queueInEvent.getOccurredAt());
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

    /**
     * 在坐席成功桥接后登记记忆坐席。
     *
     * <p>仅当 {@code stickyAgentEnabled} 已对当前队列开启、客户主叫号码可识别且接听坐席 ID 存在时落 Redis；
     * 任一条件不满足直接跳过，不影响通话流程。
     */
    private void recordStickyAgentIfEnabled(Long sessionId, CallRecord leg, Long queueId) {
        if (queueId == null || leg == null || leg.getAgentId() == null) {
            log.info("跳过登记队列记忆坐席，缺少队列或坐席信息，sessionId={}，queueId={}，agentId={}",
                sessionId, queueId, leg == null ? null : leg.getAgentId());
            return;
        }
        CallCenterResourceQueryService.QueueInfo queue = resourceQueryService.findQueueById(queueId);
        if (queue == null || !Boolean.TRUE.equals(queue.stickyAgentEnabled())) {
            log.info("跳过登记队列记忆坐席，队列未开启记忆坐席，sessionId={}，queueId={}，queueExists={}",
                sessionId, queueId, queue != null);
            return;
        }
        CallSession session = sessionMapper.selectById(sessionId);
        if (session == null || StringUtils.isBlank(session.getCallerNumber())) {
            log.info("跳过登记队列记忆坐席，未识别客户主叫号码，sessionId={}，queueId={}", sessionId, queueId);
            return;
        }
        String tenantId = StringUtils.isNotBlank(leg.getTenantId()) ? leg.getTenantId() : session.getTenantId();
        stickyAgentRegistry.recordStickyAgent(tenantId, queueId, session.getCallerNumber(), leg.getAgentId());
    }

    private record QueueEntryInfo(Long queueId, String queueCode, String queueName, LocalDateTime occurredAt) {
    }
}
