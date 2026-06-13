package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteContext;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteHandler;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteHandlerRegistry;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DialplanXmlCurlHandler implements FreeSwitchXmlCurlHandler {
    private final PhoneNumberQueryService phoneNumberQueryService;
    private final SipAccountQueryService sipAccountQueryService;
    private final FreeSwitchDialplanXmlRenderer dialplanXmlRenderer;
    private final IvrDialplanQueryService ivrDialplanQueryService;
    private final DialplanRouteHandlerRegistry routeHandlerRegistry;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        return "dialplan".equals(request.section());
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        String destinationNumber = destinationNumber(request);
        String domain = domain(request);
        String context = context(request);
        Long internalIvrFlowId = internalIvrFlowId(destinationNumber);
        if (internalIvrFlowId != null) {
            String activeFlowId = request.firstValue("variable_callnexus_ivr_flow_id");
            if (activeFlowId == null || activeFlowId.isBlank()) activeFlowId = request.firstValue("callnexus_ivr_flow_id");
            if (!String.valueOf(internalIvrFlowId).equals(activeFlowId)) {
                log.warn("拒绝未携带有效流程上下文的IVR内部目标，destinationNumber={}，tenantId={}", destinationNumber, request.tenantId());
                return FreeSwitchXmlRenderer.notFound();
            }
            return ivrDialplanQueryService.renderPublishedFlow(request.tenantId(), internalIvrFlowId, null,
                destinationNumber, context, domain);
        }
        SipDirectoryAccountResponse internalAccount = findInternalAccount(request, context, domain, destinationNumber);
        if (internalAccount != null) {
            String xml = dialplanXmlRenderer.renderInternalExtensionRoute(internalAccount, context);
            log.info("FreeSWITCH 动态拨号计划匹配到内部分机路由，context={}，extension={}，domain={}，callerNumber={}，tenantId={}，返回XML长度={}",
                context, destinationNumber, internalAccount.getDomain(), callerNumber(request), request.tenantId(), xml.length());
            return xml;
        }
        PhoneNumberDialplanRouteResponse route = phoneNumberQueryService.findDialplanRoute(request.tenantId(), domain, destinationNumber);
        if (route == null) {
            PhoneNumberOutboundRouteResponse outboundRoute = findOutboundRoute(request, context);
            if (outboundRoute != null) {
                String xml = dialplanXmlRenderer.renderOutboundRoute(outboundRoute, context, destinationNumber);
                log.info("FreeSWITCH 动态拨号计划匹配到默认外呼路由，context={}，destinationNumber={}，callerNumber={}，gatewayCode={}，callerIdNumber={}，tenantId={}，返回XML长度={}",
                    context, destinationNumber, callerNumber(request), outboundRoute.getGatewayCode(), outboundRoute.getNumber(),
                    request.tenantId(), xml.length());
                return xml;
            }
            log.info("FreeSWITCH 动态拨号计划请求未匹配到号码路由，context={}，domain={}，destinationNumber={}，callerNumber={}，tenantId={}",
                context, domain, destinationNumber, callerNumber(request), request.tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }
        DialplanRouteHandler routeHandler = routeHandlerRegistry.find(route.getRouteType()).orElse(null);
        if (routeHandler == null) {
            log.info("FreeSWITCH 动态拨号计划匹配到暂不支持的路由类型，number={}，routeType={}，tenantId={}",
                route.getNumber(), route.getRouteType(), request.tenantId());
            return FreeSwitchXmlRenderer.notFound();
        }
        return routeHandler.render(new DialplanRouteContext(request, route, context, callerNumber(request)));
    }

    private SipDirectoryAccountResponse findInternalAccount(FreeSwitchXmlCurlRequest request, String context,
                                                             String domain, String destinationNumber) {
        if (!"default".equalsIgnoreCase(context)) return null;
        return sipAccountQueryService.findDirectoryAccount(request.tenantId(), domain, destinationNumber);
    }

    private PhoneNumberOutboundRouteResponse findOutboundRoute(FreeSwitchXmlCurlRequest request, String context) {
        if (!"default".equalsIgnoreCase(context)) return null;
        return phoneNumberQueryService.findDefaultOutboundRoute(
            request.tenantId(), domain(request), request.firstValue("FreeSWITCH-IPv4"));
    }

    private String destinationNumber(FreeSwitchXmlCurlRequest request) {
        String value = request.firstValue("destination_number");
        if (value == null || value.isBlank()) value = request.firstValue("Caller-Destination-Number");
        if (value == null || value.isBlank()) value = request.firstValue("Hunt-Destination-Number");
        if (value == null || value.isBlank()) value = request.firstValue("variable_destination_number");
        if (value == null || value.isBlank()) value = request.firstValue("sip_to_user");
        if (value == null || value.isBlank()) value = request.firstValue("variable_sip_to_user");
        if (value == null || value.isBlank()) value = request.firstValue("sip_req_user");
        if (value == null || value.isBlank()) value = request.firstValue("variable_sip_req_user");
        return normalizeDialedNumber(value);
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

    private String normalizeDialedNumber(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.regionMatches(true, 0, "sip:", 0, 4)) normalized = normalized.substring(4);
        if (normalized.regionMatches(true, 0, "tel:", 0, 4)) normalized = normalized.substring(4);
        int atIndex = normalized.indexOf('@');
        if (atIndex > 0) normalized = normalized.substring(0, atIndex);
        int parameterIndex = normalized.indexOf(';');
        if (parameterIndex > 0) normalized = normalized.substring(0, parameterIndex);
        return normalized.trim();
    }

    private Long internalIvrFlowId(String destinationNumber) {
        if (destinationNumber == null || !destinationNumber.startsWith("callnexus_ivr_")) return null;
        String remainder = destinationNumber.substring("callnexus_ivr_".length());
        int separator = remainder.indexOf('_');
        if (separator <= 0) return null;
        try {
            return Long.valueOf(remainder.substring(0, separator));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
