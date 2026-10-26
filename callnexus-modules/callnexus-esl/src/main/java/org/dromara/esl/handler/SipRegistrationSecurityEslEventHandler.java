package org.dromara.esl.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.constant.EslEventNames;
import org.dromara.esl.domain.FreeSwitchEslEvent;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.sip.security.domain.request.SipRegistrationEventRequest;
import org.dromara.resource.sip.security.service.SipRegistrationSecurityService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SipRegistrationSecurityEslEventHandler implements EslEventHandler {
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final SipRegistrationSecurityService securityService;

    @Override
    public boolean supports(FreeSwitchEslEvent event) {
        if (!EslEventNames.CUSTOM.equals(event.eventName())) return false;
        String subclass = event.firstHeader("Event-Subclass", "event-subclass");
        return EslEventNames.SUBCLASS_SOFIA_REGISTER.equals(subclass)
            || EslEventNames.SUBCLASS_SOFIA_REGISTER_FAILURE.equals(subclass);
    }

    @Override
    public void handle(FreeSwitchEslEvent event) {
        String tenantId = nodeQueryService.findTenantId(event.nodeId());
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("忽略无法确定租户的SIP注册安全事件，nodeId={}", event.nodeId());
            return;
        }
        String subclass = event.firstHeader("Event-Subclass", "event-subclass");
        SipRegistrationEventRequest request = new SipRegistrationEventRequest();
        request.setTenantId(tenantId);
        request.setNodeId(event.nodeId());
        request.setExtension(event.firstHeader(
            "from-user", "From-User", "to-user", "To-User",
            "sip_number_alias", "number_alias", "user", "User"));
        request.setAuthUsername(event.firstHeader(
            "auth-user", "Auth-User", "authorization-user", "Authorization-User",
            "sip_auth_username", "username", "user_name"));
        request.setSourceIp(event.firstHeader("network-ip", "Network-IP", "network_addr", "Network-Addr"));
        request.setSourcePort(parseInteger(event.firstHeader("network-port", "Network-Port")));
        request.setTransport(event.firstHeader("network-protocol", "Network-Protocol", "sip-via-protocol", "Sip-Via-Protocol"));
        request.setUserAgent(event.firstHeader("user-agent", "User-Agent"));
        request.setResult(EslEventNames.SUBCLASS_SOFIA_REGISTER.equals(subclass) ? "SUCCESS" : "FAILURE");
        request.setReasonCode(EslEventNames.SUBCLASS_SOFIA_REGISTER.equals(subclass)
            ? "REGISTERED" : "AUTHENTICATION_FAILED");
        request.setOccurredAt(resolveOccurredAt(event));
        request.setEventKey(eventKey(event, subclass, request));
        securityService.record(request);
    }

    private LocalDateTime resolveOccurredAt(FreeSwitchEslEvent event) {
        String timestamp = event.firstHeader("Event-Date-Timestamp", "event-date-timestamp");
        if (timestamp != null) {
            try {
                long micros = Long.parseLong(timestamp);
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000), ZoneId.systemDefault());
            } catch (NumberFormatException ignored) {
                // Fall back to the application clock when a node sends a malformed timestamp.
            }
        }
        return LocalDateTime.now();
    }

    private String eventKey(FreeSwitchEslEvent event, String subclass, SipRegistrationEventRequest request) {
        String source = String.join("|",
            String.valueOf(event.nodeId()), subclass,
            nullToEmpty(event.firstHeader("Event-Date-Timestamp", "event-date-timestamp")),
            nullToEmpty(event.firstHeader("call-id", "Call-ID")),
            nullToEmpty(request.getExtension()), nullToEmpty(request.getSourceIp()));
        return "esl-" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
