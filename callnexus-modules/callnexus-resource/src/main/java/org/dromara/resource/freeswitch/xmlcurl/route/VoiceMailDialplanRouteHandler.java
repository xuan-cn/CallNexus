package org.dromara.resource.freeswitch.xmlcurl.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.voicemail.domain.response.VoiceMailDialplanResponse;
import org.dromara.resource.voicemail.service.VoiceMailBoxQueryService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceMailDialplanRouteHandler implements DialplanRouteHandler {

    private final VoiceMailBoxQueryService voiceMailBoxQueryService;
    private final FreeSwitchDialplanXmlRenderer dialplanXmlRenderer;

    @Override
    public String routeType() {
        return "VOICEMAIL";
    }

    @Override
    public String render(DialplanRouteContext context) {
        Long boxId;
        try {
            boxId = Long.valueOf(context.route().getRouteTarget());
        } catch (NumberFormatException exception) {
            log.warn("FreeSWITCH 动态拨号计划的语音留言目标格式错误，number={}，routeTarget={}，tenantId={}",
                context.route().getNumber(), context.route().getRouteTarget(), context.request().tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }
        VoiceMailDialplanResponse box = voiceMailBoxQueryService.findAvailableBox(
            context.request().tenantId(), boxId, context.route().getNodeId());
        if (box == null) {
            log.warn("FreeSWITCH 动态拨号计划未找到当前节点可用的语音留言箱，number={}，boxId={}，nodeId={}，tenantId={}",
                context.route().getNumber(), boxId, context.route().getNodeId(), context.request().tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }
        String xml = dialplanXmlRenderer.renderVoiceMailRoute(context.route(), box, context.dialplanContext());
        log.info("FreeSWITCH 动态拨号计划匹配到语音留言路由，number={}，boxId={}，nodeId={}，tenantId={}，返回XML长度={}",
            context.route().getNumber(), boxId, context.route().getNodeId(), context.request().tenantId(), xml.length());
        return xml;
    }
}
