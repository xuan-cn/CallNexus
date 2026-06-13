package org.dromara.resource.freeswitch.xmlcurl.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IvrDialplanRouteHandler implements DialplanRouteHandler {

    private final IvrDialplanQueryService ivrDialplanQueryService;

    @Override
    public String routeType() {
        return "IVR";
    }

    @Override
    public String render(DialplanRouteContext context) {
        String xml = ivrDialplanQueryService.renderPublishedFlow(
            context.request().tenantId(),
            Long.valueOf(context.route().getRouteTarget()),
            context.route().getNodeId(),
            context.route().getNumber(),
            context.dialplanContext(),
            context.route().getSipDomain()
        );
        log.info("FreeSWITCH 动态拨号计划匹配到IVR路由，number={}，ivrId={}，nodeId={}，tenantId={}，返回XML长度={}",
            context.route().getNumber(), context.route().getRouteTarget(), context.route().getNodeId(),
            context.request().tenantId(), xml.length());
        return xml;
    }
}
