package org.dromara.resource.freeswitch.xmlcurl.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExtensionDialplanRouteHandler implements DialplanRouteHandler {

    private final FreeSwitchDialplanXmlRenderer dialplanXmlRenderer;
    private final SipAccountQueryService sipAccountQueryService;

    @Override
    public String routeType() {
        return "EXTENSION";
    }

    @Override
    public String render(DialplanRouteContext context) {
        SipDirectoryAccountResponse account = sipAccountQueryService.findDirectoryAccountByExtension(
            context.request().tenantId(), context.route().getSipDomain(), context.route().getRouteTarget());
        if (account == null || account.getExtension() == null || account.getExtension().isBlank()) {
            log.warn("固定分机路由未找到可用 SIP 鉴权账号，number={}，extension={}，domain={}，tenantId={}",
                context.route().getNumber(), context.route().getRouteTarget(), context.route().getSipDomain(),
                context.request().tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }

        context.route().setRouteTarget(account.getExtension());
        String xml = dialplanXmlRenderer.renderExtensionRoute(context.route(), context.dialplanContext());
        log.info("FreeSWITCH 动态拨号计划匹配到固定分机路由，context={}，number={}，extension={}，authUsername={}，domain={}，callerNumber={}，tenantId={}，返回XML长度={}",
            context.dialplanContext(), context.route().getNumber(), account.getExtension(), account.getAuthUsername(),
            context.route().getSipDomain(), context.callerNumber(), context.request().tenantId(), xml.length());
        return xml;
    }
}
