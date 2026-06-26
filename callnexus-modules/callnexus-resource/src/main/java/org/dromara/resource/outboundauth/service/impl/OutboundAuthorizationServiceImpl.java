package org.dromara.resource.outboundauth.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationResult;
import org.dromara.resource.outboundauth.service.OutboundAuthorizationService;
import org.dromara.resource.outboundline.service.OutboundLinePolicyService;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundAuthorizationServiceImpl implements OutboundAuthorizationService {

    private final SipAccountQueryService sipAccountQueryService;
    private final PhoneNumberQueryService phoneNumberQueryService;
    private final OutboundLinePolicyService outboundLinePolicyService;

    @Override
    public OutboundAuthorizationResult authorize(OutboundAuthorizationCommand command) {
        String normalizedCallee = normalizeDialNumber(command.calleeNumber());
        if (StringUtils.isBlank(normalizedCallee)) {
            return OutboundAuthorizationResult.reject("OUTBOUND_NUMBER_EMPTY", "外呼号码不能为空", normalizedCallee);
        }
        if (!isValidDialNumber(normalizedCallee)) {
            return OutboundAuthorizationResult.reject("OUTBOUND_NUMBER_INVALID", "外呼号码格式不正确", normalizedCallee);
        }
        if (command.nodeId() != null
            && sipAccountQueryService.findEnabledByNodeAndExtension(command.nodeId(), normalizedCallee) != null) {
            log.info("外呼授权通过：目标为内部分机，sourceType={}，nodeId={}，caller={}，callee={}，tenantId={}",
                command.sourceType(), command.nodeId(), command.callerExtension(), normalizedCallee, command.tenantId());
            return OutboundAuthorizationResult.allowInternal(normalizedCallee);
        }

        PhoneNumberOutboundRouteResponse route = resolveOutboundRoute(command);
        if (route == null) {
            if (command.callerNumberId() != null) {
                log.warn("外呼授权拒绝：指定外呼主叫号码不可用，sourceType={}，nodeId={}，callerNumberId={}，caller={}，callee={}，tenantId={}",
                    command.sourceType(), command.nodeId(), command.callerNumberId(),
                    command.callerExtension(), normalizedCallee, command.tenantId());
                return OutboundAuthorizationResult.reject("OUTBOUND_CALLER_NUMBER_UNAVAILABLE", "指定外呼主叫号码不可用或未绑定可用网关", normalizedCallee);
            }
            log.warn("外呼授权拒绝：未配置可用默认外呼号码路由，sourceType={}，nodeId={}，sipDomain={}，switchIpv4={}，caller={}，callee={}，tenantId={}",
                command.sourceType(), command.nodeId(), command.sipDomain(), command.switchIpv4(),
                command.callerExtension(), normalizedCallee, command.tenantId());
            return OutboundAuthorizationResult.reject("OUTBOUND_ROUTE_NOT_CONFIGURED", "未配置默认外呼号码路由", normalizedCallee);
        }

        log.info("外呼授权通过：使用外呼号码路由，sourceType={}，nodeId={}，caller={}，callee={}，gatewayCode={}，callerIdNumber={}，policyCode={}，policyType={}，tenantId={}",
            command.sourceType(), command.nodeId(), command.callerExtension(), normalizedCallee,
            route.getGatewayCode(), route.getNumber(), route.getPolicyCode(), route.getPolicyType(), command.tenantId());
        return OutboundAuthorizationResult.allowExternal(normalizedCallee, route);
    }

    private PhoneNumberOutboundRouteResponse resolveOutboundRoute(OutboundAuthorizationCommand command) {
        if (command.callerNumberId() != null) {
            return phoneNumberQueryService.findOutboundRouteByNumberId(command.tenantId(), command.nodeId(), command.callerNumberId());
        }
        if (command.nodeId() != null) {
            PhoneNumberOutboundRouteResponse policyRoute = outboundLinePolicyService.selectRoute(command.tenantId(), command.nodeId());
            return policyRoute != null ? policyRoute : phoneNumberQueryService.findDefaultOutboundRoute(command.tenantId(), command.nodeId());
        }
        return phoneNumberQueryService.findDefaultOutboundRoute(command.tenantId(), command.sipDomain(), command.switchIpv4());
    }

    private String normalizeDialNumber(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.regionMatches(true, 0, "sip:", 0, 4)) normalized = normalized.substring(4);
        if (normalized.regionMatches(true, 0, "tel:", 0, 4)) normalized = normalized.substring(4);
        int atIndex = normalized.indexOf('@');
        if (atIndex > 0) normalized = normalized.substring(0, atIndex);
        int parameterIndex = normalized.indexOf(';');
        if (parameterIndex > 0) normalized = normalized.substring(0, parameterIndex);
        normalized = normalized
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
            .replace("（", "")
            .replace("）", "");
        if (normalized.startsWith("00") && normalized.length() > 2) {
            normalized = "+" + normalized.substring(2);
        }
        return normalized.trim();
    }

    private boolean isValidDialNumber(String value) {
        if (value.startsWith("+")) {
            return value.length() > 1 && value.substring(1).matches("\\d{3,32}");
        }
        return value.matches("\\d{2,32}|\\*\\d{1,31}|#\\d{1,31}");
    }
}
