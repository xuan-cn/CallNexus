package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.dialplan.FreeSwitchDialplanXmlRenderer;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteContext;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteHandler;
import org.dromara.resource.freeswitch.xmlcurl.route.DialplanRouteHandlerRegistry;
import org.dromara.resource.event.queue.QueueSatisfactionSignalEvent;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationResult;
import org.dromara.resource.outboundauth.service.OutboundAuthorizationService;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;
import org.dromara.resource.queue.service.CallQueueQueryService;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.dromara.resource.voicemail.domain.response.VoiceMailDialplanResponse;
import org.dromara.resource.voicemail.service.VoiceMailBoxQueryService;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;

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
    private final CallQueueQueryService callQueueQueryService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        return "dialplan".equals(request.section());
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        String destinationNumber = destinationNumber(request);
        String domain = domain(request);
        String context = context(request);
        Long queueSatisfactionId = queueTransferId(destinationNumber, "callnexus_queue_satisfaction_");
        if (queueSatisfactionId != null) {
            if (!"QUEUE_SATISFACTION".equals(firstValue(request,
                "variable_callnexus_internal_transfer", "callnexus_internal_transfer"))) {
                log.warn("拒绝未携带队列评价标记的内部目标，destinationNumber={}，tenantId={}",
                    destinationNumber, request.tenantId());
                return FreeSwitchXmlRenderer.notFound();
            }
            String businessCallId = firstValue(request,
                "variable_callnexus_business_call_id", "callnexus_business_call_id");
            String customerLegUuid = firstValue(request, "variable_uuid", "Unique-ID");
            String digit = firstValue(request,
                "variable_callnexus_satisfaction_digit", "callnexus_satisfaction_digit");
            eventPublisher.publishEvent(new QueueSatisfactionSignalEvent(
                request.tenantId(), businessCallId, customerLegUuid, queueSatisfactionId, nodeId(request), digit));
            log.info("FreeSWITCH 队列挂机评价结果已接收，businessCallId={}，queueId={}，customerLegUuid={}，digit={}，tenantId={}",
                businessCallId, queueSatisfactionId, customerLegUuid, digit, request.tenantId());
            return dialplanXmlRenderer.renderQueueSatisfactionResultRoute(destinationNumber, context);
        }
        Long queuePostId = queueTransferId(destinationNumber, "callnexus_queue_post_");
        if (queuePostId != null) {
            if (!isQueuePostTransfer(request)) {
                log.warn("拒绝未携带队列后处理标记的内部目标，destinationNumber={}，tenantId={}",
                    destinationNumber, request.tenantId());
                return FreeSwitchXmlRenderer.notFound();
            }
            Long freeSwitchNodeId = nodeId(request);
            CallQueueDialplanResponse queue = callQueueQueryService.findAvailableQueue(
                request.tenantId(), queuePostId, freeSwitchNodeId, callerNumber(request));
            if (queue == null) {
                log.warn("队列后处理失败，未找到当前节点可用队列，queueId={}，nodeId={}，tenantId={}",
                    queuePostId, freeSwitchNodeId, request.tenantId());
                return FreeSwitchXmlRenderer.notFound();
            }
            fillQueueOutboundGateway(queue, request.tenantId(), freeSwitchNodeId);
            boolean agentBridged = requestBoolean(request, "variable_cc_agent_bridged", "cc_agent_bridged");
            boolean satisfactionSkipped = requestBoolean(request,
                "variable_callnexus_satisfaction_skip", "callnexus_satisfaction_skip");
            boolean stickyHit = requestBoolean(request,
                "variable_callnexus_queue_sticky_hit", "callnexus_queue_sticky_hit");
            boolean stickyFallback = requestBoolean(request,
                "variable_callnexus_queue_sticky_fallback", "callnexus_queue_sticky_fallback");
            String originateDisposition = firstValue(request,
                "variable_originate_disposition", "originate_disposition");
            boolean stickyDirectSucceeded = "SUCCESS".equalsIgnoreCase(originateDisposition);
            String xml = dialplanXmlRenderer.renderQueuePostRoute(destinationNumber, queue, context,
                agentBridged, satisfactionSkipped, stickyHit && !stickyFallback, stickyDirectSucceeded);
            log.info("FreeSWITCH 队列后处理路由已生成，queueId={}，nodeId={}，agentBridged={}，ccCause={}，"
                    + "satisfactionSkipped={}，stickyHit={}，stickyFallback={}，originateDisposition={}，tenantId={}",
                queuePostId, freeSwitchNodeId, agentBridged,
                firstValue(request, "variable_cc_cause", "cc_cause"), satisfactionSkipped,
                stickyHit, stickyFallback, originateDisposition, request.tenantId());
            return xml;
        }
        Long queueTransferIvrFlowId = queueTransferId(destinationNumber, "callnexus_queue_ivr_");
        if (queueTransferIvrFlowId != null) {
            if (!isQueueInternalTransfer(request)) {
                log.warn("拒绝未携带队列内部转接标记的 IVR 目标，destinationNumber={}，tenantId={}",
                    destinationNumber, request.tenantId());
                return FreeSwitchXmlRenderer.notFound();
            }
            return ivrDialplanQueryService.renderPublishedFlow(request.tenantId(), queueTransferIvrFlowId, nodeId(request),
                destinationNumber, context, domain, callerNumber(request));
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
                destinationNumber, context, domain, callerNumber(request));
        }

        SipDirectoryAccountResponse internalAccount = findInternalAccount(request, context, domain, destinationNumber);
        if (internalAccount != null) {
            String xml = dialplanXmlRenderer.renderInternalExtensionRoute(internalAccount, context);
            log.info("FreeSWITCH 动态拨号计划匹配到内部分机路由，context={}，extension={}，authUsername={}，domain={}，callerNumber={}，tenantId={}，返回XML长度={}",
                context, internalAccount.getExtension(), internalAccount.getAuthUsername(), internalAccount.getDomain(),
                callerNumber(request), request.tenantId(), xml.length());
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
        return sipAccountQueryService.findDirectoryAccountByExtension(request.tenantId(), domain, destinationNumber);
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

    private boolean isQueuePostTransfer(FreeSwitchXmlCurlRequest request) {
        return "QUEUE_POST".equals(firstValue(request,
            "variable_callnexus_internal_transfer", "callnexus_internal_transfer"));
    }

    private boolean requestBoolean(FreeSwitchXmlCurlRequest request, String... names) {
        return "true".equalsIgnoreCase(firstValue(request, names));
    }

    private String firstValue(FreeSwitchXmlCurlRequest request, String... names) {
        for (String name : names) {
            String value = request.firstValue(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private void fillQueueOutboundGateway(CallQueueDialplanResponse queue, String tenantId, Long freeSwitchNodeId) {
        if (!Boolean.TRUE.equals(queue.getBusyTransferMobile())
            && !Boolean.TRUE.equals(queue.getAgentTimeoutTransferMobile())) {
            return;
        }
        PhoneNumberOutboundRouteResponse outbound = phoneNumberQueryService.findDefaultOutboundRoute(tenantId, freeSwitchNodeId);
        if (outbound != null) {
            queue.setOutboundGatewayCode(outbound.getGatewayCode());
        }
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
