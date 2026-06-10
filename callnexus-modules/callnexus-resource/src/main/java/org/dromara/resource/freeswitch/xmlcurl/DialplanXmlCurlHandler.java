package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DialplanXmlCurlHandler implements FreeSwitchXmlCurlHandler {
    private final PhoneNumberQueryService phoneNumberQueryService;
    private final FreeSwitchDialplanXmlRenderer dialplanXmlRenderer;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        return "dialplan".equals(request.section());
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        String destinationNumber = destinationNumber(request);
        String domain = domain(request);
        String context = context(request);
        PhoneNumberDialplanRouteResponse route = phoneNumberQueryService.findDialplanRoute(request.tenantId(), domain, destinationNumber);
        if (route == null) {
            log.info("FreeSWITCH 动态拨号计划请求未匹配到号码路由，context={}，domain={}，destinationNumber={}，callerNumber={}，tenantId={}",
                context, domain, destinationNumber, callerNumber(request), request.tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }
        if ("EXTENSION".equals(route.getRouteType())) {
            String xml = dialplanXmlRenderer.renderExtensionRoute(route, context);
            log.info("FreeSWITCH 动态拨号计划匹配到固定分机路由，context={}，number={}，extension={}，domain={}，callerNumber={}，tenantId={}，返回XML长度={}",
                context, route.getNumber(), route.getRouteTarget(), route.getSipDomain(), callerNumber(request), request.tenantId(), xml.length());
            return xml;
        }
        log.info("FreeSWITCH 动态拨号计划匹配到暂不支持的路由类型，number={}，routeType={}，tenantId={}",
            route.getNumber(), route.getRouteType(), request.tenantId());
        return FreeSwitchXmlRenderer.notFound();
    }

    private String destinationNumber(FreeSwitchXmlCurlRequest request) {
        String value = request.firstValue("destination_number");
        if (value == null || value.isBlank()) value = request.firstValue("Caller-Destination-Number");
        if (value == null || value.isBlank()) value = request.firstValue("Hunt-Destination-Number");
        return value;
    }

    private String domain(FreeSwitchXmlCurlRequest request) {
        String value = request.domain();
        if (value == null || value.isBlank()) value = request.firstValue("variable_domain_name");
        if (value == null || value.isBlank()) value = request.firstValue("FreeSWITCH-IPv4");
        return value;
    }

    private String context(FreeSwitchXmlCurlRequest request) {
        String value = request.firstValue("Caller-Context");
        if (value == null || value.isBlank()) value = request.firstValue("context");
        return value == null || value.isBlank() ? "public" : value;
    }

    private String callerNumber(FreeSwitchXmlCurlRequest request) {
        String value = request.firstValue("caller_id_number");
        if (value == null || value.isBlank()) value = request.firstValue("Caller-Caller-ID-Number");
        if (value == null || value.isBlank()) value = request.firstValue("variable_caller_id_number");
        return value;
    }
}
