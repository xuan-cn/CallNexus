package org.dromara.resource.freeswitch.xmlcurl.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.event.queue.QueueEntrySignalEvent;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
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
    private final PhoneNumberQueryService phoneNumberQueryService;

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
            context.request().tenantId(), queueId, context.route().getNodeId(), context.callerNumber());
        if (queue == null) {
            log.warn("FreeSWITCH 动态拨号计划未找到目标节点可用的呼叫队列，number={}，queueId={}，nodeId={}，tenantId={}",
                context.route().getNumber(), queueId, context.route().getNodeId(), context.request().tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }
        fillOutboundGatewayCode(queue, context);
        String xml = dialplanXmlRenderer.renderQueueRoute(context.route(), queue, context.dialplanContext());
        publishQueueEntrySignal(context, queue);
        if (Boolean.TRUE.equals(queue.getStickyAgentEnabled()) && StringUtils.isNotBlank(queue.getStickyAgentTarget())) {
            log.info("FreeSWITCH 动态拨号计划命中记忆坐席，绕开 mod_callcenter 直拨分机，number={}，queueId={}，queueCode={}，stickyTarget={}，callerNumber={}，nodeId={}，tenantId={}，返回XML长度={}",
                context.route().getNumber(), queue.getId(), queue.getQueueCode(), queue.getStickyAgentTarget(),
                context.callerNumber(), context.route().getNodeId(), context.request().tenantId(), xml.length());
        } else {
            log.info("FreeSWITCH 动态拨号计划匹配到呼叫队列路由，number={}，queueId={}，queueCode={}，nodeId={}，tenantId={}，返回XML长度={}",
                context.route().getNumber(), queue.getId(), queue.getQueueCode(), context.route().getNodeId(),
                context.request().tenantId(), xml.length());
        }
        return xml;
    }

    /**
     * 在 dialplan 渲染前补全转手机所需的默认外呼网关编码。
     *
     * <p>放在 resource 模块入口而不是 agent 队列查询里，是为了避免 agent 模块依赖
     * {@link PhoneNumberQueryService}（其实现会回头依赖到 agent），从而打破 Spring Bean 循环引用。
     */
    private void fillOutboundGatewayCode(CallQueueDialplanResponse queue, DialplanRouteContext context) {
        if (!Boolean.TRUE.equals(queue.getBusyTransferMobile())
            && !Boolean.TRUE.equals(queue.getAgentTimeoutTransferMobile())) {
            return;
        }
        try {
            PhoneNumberOutboundRouteResponse outbound = phoneNumberQueryService.findDefaultOutboundRoute(
                context.request().tenantId(), context.route().getNodeId());
            if (outbound != null) {
                queue.setOutboundGatewayCode(outbound.getGatewayCode());
            } else {
                log.warn("队列开启转手机但未找到默认外呼网关，转手机分支将不生效，queueId={}，queueCode={}，nodeId={}，tenantId={}",
                    queue.getId(), queue.getQueueCode(), context.route().getNodeId(), context.request().tenantId());
            }
        } catch (Exception exception) {
            log.warn("解析默认外呼网关失败，转手机分支将不生效，queueId={}，queueCode={}，nodeId={}，tenantId={}",
                queue.getId(), queue.getQueueCode(), context.route().getNodeId(), context.request().tenantId(), exception);
        }
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