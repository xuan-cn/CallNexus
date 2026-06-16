package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.CallEvent;
import org.dromara.call.domain.CallRecord;
import org.dromara.call.mapper.CallEventMapper;
import org.dromara.call.mapper.CallRecordMapper;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.event.businesshours.BusinessHoursRouteEvaluatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessHoursRouteEventListener {
    private final CallRecordMapper recordMapper;
    private final CallEventMapper eventMapper;

    @EventListener
    public void onEvaluated(BusinessHoursRouteEvaluatedEvent event) {
        try {
            TenantHelper.dynamic(event.tenantId(), () -> persist(event));
        } catch (Exception exception) {
            log.warn("记录工作时间路由判断事件失败，businessCallId={}，原因={}", event.businessCallId(), exception.getMessage());
        }
    }

    private void persist(BusinessHoursRouteEvaluatedEvent event) {
        CallRecord record = recordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
            .and(wrapper -> wrapper.eq(CallRecord::getChannelUuid, event.channelUuid())
                .or().eq(CallRecord::getCallUuid, event.businessCallId()))
            .last("limit 1"));
        if (record == null || record.getSessionId() == null) return;
        CallEvent timeline = new CallEvent();
        timeline.setSessionId(record.getSessionId());
        timeline.setChannelUuid(event.channelUuid());
        timeline.setEventType("BUSINESS_HOURS_ROUTE");
        timeline.setFromTarget(event.inBusinessHours() ? "工作时间内" : "工作时间外");
        timeline.setToTarget(event.targetType() + (event.target() == null ? "" : ":" + event.target()));
        timeline.setOccurredAt(LocalDateTime.now());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("planId", event.planId());
        metadata.put("inBusinessHours", event.inBusinessHours());
        metadata.put("reason", event.reason());
        metadata.put("timezone", event.timezone());
        metadata.put("targetType", event.targetType());
        metadata.put("target", event.target());
        timeline.setMetadataJson(JsonUtils.toJsonString(metadata));
        eventMapper.insert(timeline);
    }
}
