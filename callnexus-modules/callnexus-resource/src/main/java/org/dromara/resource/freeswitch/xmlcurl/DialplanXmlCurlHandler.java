package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteContext;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteHandler;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteHandlerRegistry;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationResult;
import org.dromara.resource.outboundauth.service.OutboundAuthorizationService;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.dromara.resource.voicemail.domain.response.VoiceMailDialplanResponse;
import org.dromara.resource.voicemail.service.VoiceMailBoxQueryService;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DialplanXmlCurlHandler implements FreeSwitchXmlCurlHandler {
    private final PhoneNumberQueryService phoneNumberQueryService;
    private final SipAccountQueryService sipAccountQueryService;
    private final FreeSwitchDialplanXmlRenderer dialplanXmlRenderer;
    private final IvrDialplanQueryService ivrDialplanQueryService;
    private final VoiceMailBoxQueryService voiceMailBoxQueryService;
    private final DialplanRouteHandlerRegistry routeHandlerRegistry;
    private final OutboundAuthorizationService outboundAuthorizationService;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        return "dialplan".equals(request.section());
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        String destinationNumber = destinationNumber(request);
        String domain = domain(request);
        String context = context(request);
        Long queueTransferIvrFlowId = queueTransferId(destinationNumber, "callnexus_queue_ivr_");
        if (queueTransferIvrFlowId != null) {
            if (!isQueueInternalTransfer(request)) {
                log.warn("拒绝未携带队列内部转接标记的 IVR 目标，destinationNumber={}，tenantId={}",
                    destinationNumber, request.tenantId());
                return FreeSwitchXmlRenderer.notFound();
            }
            return ivrDialplanQueryService.renderPublishedFlow(request.tenantId(), queueTransferIvrFlowId, nodeId(request),
                destinationNumber, context, domain);
        }
        Long queueTransferVoiceMailBoxId = queueTransferId(destinationNumber, "callnexus_queue_voicemail_");
        if (queueTransferVoiceMailBoxId != null) {
            if (!isQueueInternalTransfer(request)) {
                log.warn("拒绝未携带队列内部转接标记的语音留言目标，destinationNumber={}，tenantId={}",
                    destinationNumber, request.tenantId());
                return FreeSwitchXmlRenderer.notFound();
            }
            VoiceMailDialplanResponse box = voiceMailBoxQueryService.findAvailableBox(request.tenantId(), queueTransferVoiceMailBoxId, nodeId(request));
            if (box == null) {
                log.warn("队列内部转语音留言失败，留言箱不可用，boxId={}，nodeId={}，tenantId={}",
                    queueTransferVoiceMailBoxId, nodeId(request), request.tenantId());
                return FreeSwitchXmlRenderer.notFound();
            }
            return dialplanXmlRenderer.renderInternalVoiceMailRoute(destinationNumber, box, context);
        }
        Long internalIvrFlowId = internalIvrFlowId(destinationNumber);
        if (internalIvrFlowId != null) {
            String activeFlowId = request.firstValue("variable_callnexus_ivr_flow_id");
            if (activeFlowId == null || activeFlowId.isBlank()) activeFlowId = request.firstValue("callnexus_ivr_flow_id");
            if (!String.valueOf(internalIvrFlowId).equals(activeFlowId)) {
                log.warn("拒绝未携带有效流程上下文的 IVR 内部目标，destinationNumber={}，tenantId={}",
                    destinationNumber, request.tenantId());
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
            OutboundAuthorizationResult authorization = authorizeOutbound(request, context, domain, destinationNumber);
            if (authorization.allowed() && authorization.external()) {
                String xml = dialplanXmlRenderer.renderOutboundRoute(authorization.outboundRoute(), context, authorization.normalizedCallee());
                log.info("FreeSWITCH 动态拨号计划匹配到默认外呼路由，context={}，destinationNumber={}，callerNumber={}，gatewayCode={}，callerIdNumber={}，tenantId={}，返回XML长度={}",
                    context, authorization.normalizedCallee(), callerNumber(request), authorization.outboundRoute().getGatewayCode(),
                    authorization.outboundRoute().getNumber(), request.tenantId(), xml.length());
                return xml;
            }
            log.info("FreeSWITCH 动态拨号计划请求未匹配到可用路由，context={}，domain={}，destinationNumber={}，callerNumber={}，tenantId={}，rejectCode={}",
                context, domain, destinationNumber, callerNumber(request), request.tenantId(), authorization.rejectCode());
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

    private OutboundAuthorizationResult authorizeOutbound(FreeSwitchXmlCurlRequest request, String context,
                                                          String domain, String destinationNumber) {
        if (!"default".equalsIgnoreCase(context)) {
            return OutboundAuthorizationResult.reject("DIALPLAN_CONTEXT_NOT_ALLOWED", "当前拨号计划上下文不允许默认外呼", destinationNumber);
        }
        return outboundAuthorizationService.authorize(new OutboundAuthorizationCommand(
            request.tenantId(),
            "FREESWITCH_DIALPLAN",
            null,
            domain,
            request.firstValue("FreeSWITCH-IPv4"),
            null,
            null,
            callerNumber(request),
            destinationNumber,
            null,
            null,
            null,
            null
        ));
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

    private Long nodeId(FreeSwitchXmlCurlRequest request) {
        String value = request.firstValue("variable_callnexus_node_id");
        if (value == null || value.isBlank()) value = request.firstValue("callnexus_node_id");
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isQueueInternalTransfer(FreeSwitchXmlCurlRequest request) {
        String value = request.firstValue("variable_callnexus_internal_transfer");
        if (value == null || value.isBlank()) value = request.firstValue("callnexus_internal_transfer");
        return "QUEUE".equals(value);
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

    private Long queueTransferId(String destinationNumber, String prefix) {
        if (destinationNumber == null || !destinationNumber.startsWith(prefix)) return null;
        try {
            return Long.valueOf(destinationNumber.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
