package org.dromara.openapi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.event.CallLifecycleEvent;
import org.dromara.call.domain.event.CallSupervisionLifecycleEvent;
import org.dromara.ai.domain.event.AiTranscriptLifecycleEvent;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.openapi.domain.OpenApiApplication;
import org.dromara.openapi.domain.OpenApiApplicationScope;
import org.dromara.openapi.domain.OpenApiEvent;
import org.dromara.openapi.domain.OpenApiEventDelivery;
import org.dromara.openapi.domain.response.OpenApiEventResponse;
import org.dromara.openapi.mapper.OpenApiApplicationMapper;
import org.dromara.openapi.mapper.OpenApiApplicationScopeMapper;
import org.dromara.openapi.mapper.OpenApiEventDeliveryMapper;
import org.dromara.openapi.mapper.OpenApiEventMapper;
import org.dromara.openapi.service.OpenApiEventService;
import org.dromara.openapi.websocket.OpenApiEventWebSocketRegistry;
import org.dromara.openapi.websocket.OpenApiEventClusterMessage;
import org.dromara.openapi.websocket.OpenApiEventWebSocketTopicListener;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenApiEventServiceImpl implements OpenApiEventService {
    private final OpenApiEventMapper eventMapper;
    private final OpenApiEventDeliveryMapper deliveryMapper;
    private final OpenApiApplicationMapper applicationMapper;
    private final OpenApiApplicationScopeMapper scopeMapper;
    private final OpenApiEventWebSocketRegistry webSocketRegistry;

    @EventListener
    public void onCallLifecycleEvent(CallLifecycleEvent source) {
        try {
            TenantHelper.dynamic(source.tenantId(), () -> publish(source));
        } catch (Exception exception) {
            log.error("OpenAPI event persistence failed without affecting call processing, tenantId={}, type={}, callId={}",
                source.tenantId(), source.eventType(), source.businessCallId(), exception);
        }
    }

    @EventListener
    public void onCallSupervisionLifecycleEvent(CallSupervisionLifecycleEvent source) {
        try {
            TenantHelper.dynamic(source.tenantId(), () -> persist(source.eventType(), source.businessCallId(),
                source.nodeId(), source.occurredAt(), source.payload()));
        } catch (Exception exception) {
            log.error("OpenAPI supervision event persistence failed without affecting call processing, tenantId={}, type={}, callId={}",
                source.tenantId(), source.eventType(), source.businessCallId(), exception);
        }
    }

    @EventListener
    public void onTranscriptLifecycleEvent(AiTranscriptLifecycleEvent source) {
        try {
            TenantHelper.dynamic(source.tenantId(), () -> persist(source.eventType(), source.businessCallId(), source.nodeId(),
                source.occurredAt(), source.payload()));
        } catch (Exception exception) {
            log.error("OpenAPI transcript event persistence failed without affecting ASR processing, tenantId={}, type={}, callId={}",
                source.tenantId(), source.eventType(), source.businessCallId(), exception);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(CallLifecycleEvent source) {
        persist(source.eventType(), source.businessCallId(), source.nodeId(), source.occurredAt(), source.payload());
    }

    private void persist(String eventType, String businessCallId, Long nodeId,
                         java.time.LocalDateTime occurredAt, Map<String, Object> payload) {
        OpenApiEvent event = new OpenApiEvent();
        event.setEventType(eventType);
        event.setBusinessCallId(businessCallId);
        event.setNodeId(nodeId);
        event.setOccurredAt(occurredAt);
        event.setPayloadJson(JsonUtils.toJsonString(payload));
        eventMapper.insert(event);

        String envelope = JsonUtils.toJsonString(response(event));
        List<OpenApiApplication> applications = subscribedApplications(eventType);
        log.info("OpenAPI event persisted, eventId={}, type={}, businessCallId={}, subscribedApplications={}",
            event.getId(), eventType, businessCallId, applications.size());
        for (OpenApiApplication application : applications) {
            if (Boolean.TRUE.equals(application.getWebsocketEnabled())) {
                publishWebSocket(application.getId(), envelope);
            }
            if (Boolean.TRUE.equals(application.getWebhookEnabled())
                && application.getWebhookUrl() != null && !application.getWebhookUrl().isBlank()) {
                OpenApiEventDelivery delivery = new OpenApiEventDelivery();
                delivery.setEventId(event.getId());
                delivery.setApplicationId(application.getId());
                delivery.setDeliveryType("WEBHOOK");
                delivery.setDeliveryStatus("PENDING");
                delivery.setAttemptCount(0);
                delivery.setNextRetryAt(occurredAt);
                deliveryMapper.insert(delivery);
            }
        }
    }

    private void publishWebSocket(Long applicationId, String envelope) {
        webSocketRegistry.send(applicationId, envelope);
        try {
            RedisUtils.publish(OpenApiEventWebSocketTopicListener.TOPIC,
                new OpenApiEventClusterMessage(applicationId, envelope, OpenApiEventWebSocketTopicListener.INSTANCE_ID));
        } catch (Exception exception) {
            log.warn("OpenAPI event Redis cluster fanout failed; local WebSocket delivery has completed, applicationId={}, error={}",
                applicationId, exception.getMessage());
        }
    }

    @Override
    public List<OpenApiEventResponse> list(Long applicationId, Long afterId, String eventType,
                                           String businessCallId, Integer pageSize) {
        OpenApiApplication application = applicationMapper.selectById(applicationId);
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) return List.of();
        Set<String> subscribed = subscribedEvents(application);
        if (subscribed.isEmpty()) return List.of();
        if (eventType != null && !eventType.isBlank() && !subscribed.contains(eventType)) return List.of();
        int limit = pageSize == null ? 100 : Math.max(1, Math.min(pageSize, 200));
        return eventMapper.selectList(new LambdaQueryWrapper<OpenApiEvent>()
                .gt(afterId != null, OpenApiEvent::getId, afterId)
                .eq(eventType != null && !eventType.isBlank(), OpenApiEvent::getEventType, eventType)
                .eq(businessCallId != null && !businessCallId.isBlank(), OpenApiEvent::getBusinessCallId, businessCallId)
                .in(!subscribed.isEmpty(), OpenApiEvent::getEventType, subscribed)
                .orderByAsc(OpenApiEvent::getId).last("limit " + limit))
            .stream().map(this::response).toList();
    }

    private List<OpenApiApplication> subscribedApplications(String eventType) {
        Set<Long> scopedIds = scopeMapper.selectList(new LambdaQueryWrapper<OpenApiApplicationScope>()
                .eq(OpenApiApplicationScope::getScopeCode, "event.subscribe"))
            .stream().map(OpenApiApplicationScope::getApplicationId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (scopedIds.isEmpty()) return List.of();
        return applicationMapper.selectList(new LambdaQueryWrapper<OpenApiApplication>()
                .in(OpenApiApplication::getId, scopedIds)
                .eq(OpenApiApplication::getEnabled, true))
            .stream().filter(value -> subscribedEvents(value).contains(eventType)).toList();
    }

    private Set<String> subscribedEvents(OpenApiApplication application) {
        if (application.getSubscribedEvents() == null || application.getSubscribedEvents().isBlank()) return Set.of();
        return new LinkedHashSet<>(JsonUtils.parseArray(application.getSubscribedEvents(), String.class));
    }

    private OpenApiEventResponse response(OpenApiEvent event) {
        OpenApiEventResponse result = new OpenApiEventResponse();
        result.setEventId(event.getId().toString());
        result.setEventType(event.getEventType());
        result.setBusinessCallId(event.getBusinessCallId());
        result.setNodeId(event.getNodeId());
        result.setOccurredAt(event.getOccurredAt());
        Map<String, Object> payload = JsonUtils.parseMap(event.getPayloadJson());
        result.setData(payload == null ? new LinkedHashMap<>() : payload);
        return result;
    }
}
