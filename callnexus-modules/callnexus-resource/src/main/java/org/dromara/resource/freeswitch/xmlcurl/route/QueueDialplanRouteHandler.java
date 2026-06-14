package org.dromara.resource.freeswitch.xmlcurl.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;
import org.dromara.resource.queue.service.CallQueueQueryService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueDialplanRouteHandler implements DialplanRouteHandler {

    private final CallQueueQueryService callQueueQueryService;
    private final FreeSwitchDialplanXmlRenderer dialplanXmlRenderer;

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
        log.info("FreeSWITCH 动态拨号计划匹配到呼叫队列路由，number={}，queueId={}，queueCode={}，nodeId={}，tenantId={}，返回XML长度={}",
            context.route().getNumber(), queue.getId(), queue.getQueueCode(), context.route().getNodeId(),
            context.request().tenantId(), xml.length());
        return xml;
    }
}
