package org.dromara.resource.freeswitch.xmlcurl.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.event.queue.QueueEntrySignalEvent;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;
import org.dromara.resource.queue.service.CallQueueQueryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueDialplanRouteHandler implements DialplanRouteHandler {

    private final CallQueueQueryService callQueueQueryService;
    private final FreeSwitchDialplanXmlRenderer dialplanXmlRenderer;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String routeType() {
        return "QUEUE";
    }

    @Override
    public String render(DialplanRouteContext context) {
        Long queueId;
        try {
            queueId = Long.valueOf(context.route().getRouteTarget());
        } catch (NumberFormatException exception) {
            log.warn("FreeSWITCH 动态拨号计划的队列路由目标格式错误，number={}，routeTarget={}，tenantId={}",
                context.route().getNumber(), context.route().getRouteTarget(), context.request().tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }
        CallQueueDialplanResponse queue = callQueueQueryService.findAvailableQueue(
            context.request().tenantId(), queueId, context.route().getNodeId());
        if (queue == null) {
            log.warn("FreeSWITCH 动态拨号计划未找到目标节点可用的呼叫队列，number={}，queueId={}，nodeId={}，tenantId={}",
                context.route().getNumber(), queueId, context.route().getNodeId(), context.request().tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }
        String xml = dialplanXmlRenderer.renderQueueRoute(context.route(), queue, context.dialplanContext());
        publishQueueEntrySignal(context, queue);
        log.info("FreeSWITCH 动态拨号计划匹配到呼叫队列路由，number={}，queueId={}，queueCode={}，nodeId={}，tenantId={}，返回XML长度={}",
            context.route().getNumber(), queue.getId(), queue.getQueueCode(), context.route().getNodeId(),
            context.request().tenantId(), xml.length());
        return xml;
    }

    /**
     * 发布"进入队列"信号事件，供 call 模块在通话时间线记录队列进入节点。
     *
     * <p>仅在 dialplan 请求携带业务通话 ID 和 channel uuid 时发布。
     * 同一会话可能多次请求 dialplan（IVR 转队列等二次路由），消费端通过 cc_call_event 去重。
     */
    private void publishQueueEntrySignal(DialplanRouteContext context, CallQueueDialplanResponse queue) {
        String businessCallId = context.request().firstValue("variable_callnexus_business_call_id");
        String channelUuid = context.request().firstValue("variable_uuid");
        if (StringUtils.isBlank(businessCallId)) {
            businessCallId = context.request().firstValue("Unique-ID");
        }
        if (StringUtils.isBlank(businessCallId) || StringUtils.isBlank(channelUuid)) {
            // 首次呼入请求可能尚未 export 业务通话变量，此时无法关联到 session，跳过发布。
            return;
        }
        try {
            eventPublisher.publishEvent(new QueueEntrySignalEvent(
                context.request().tenantId(),
                businessCallId,
                channelUuid,
                queue.getId(),
                queue.getQueueCode(),
                queue.getQueueName(),
                context.route().getNodeId()
            ));
        } catch (Exception exception) {
            log.warn("发布进入队列信号事件失败，不影响拨号计划返回，businessCallId={}，queueCode={}",
                businessCallId, queue.getQueueCode(), exception);
        }
    }
}
