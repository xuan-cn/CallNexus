package org.dromara.resource.freeswitch.xmlcurl.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExtensionDialplanRouteHandler implements DialplanRouteHandler {

    private final FreeSwitchDialplanXmlRenderer dialplanXmlRenderer;

    @Override
    public String routeType() {
        return "EXTENSION";
    }

    @Override
    public String render(DialplanRouteContext context) {
        String xml = dialplanXmlRenderer.renderExtensionRoute(context.route(), context.dialplanContext());
        log.info("FreeSWITCH 动态拨号计划匹配到固定分机路由，context={}，number={}，extension={}，domain={}，callerNumber={}，tenantId={}，返回XML长度={}",
            context.dialplanContext(), context.route().getNumber(), context.route().getRouteTarget(),
            context.route().getSipDomain(), context.callerNumber(), context.request().tenantId(), xml.length());
        return xml;
    }
}
