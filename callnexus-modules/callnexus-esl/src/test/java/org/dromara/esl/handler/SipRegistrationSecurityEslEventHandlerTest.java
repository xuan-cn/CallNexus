package org.dromara.esl.handler;

import org.dromara.esl.domain.FreeSwitchEslEvent;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.sip.security.domain.request.SipRegistrationEventRequest;
import org.dromara.resource.sip.security.service.SipRegistrationSecurityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class SipRegistrationSecurityEslEventHandlerTest {
    @Mock
    private FreeSwitchNodeQueryService nodeQueryService;
    @Mock
    private SipRegistrationSecurityService securityService;
    @InjectMocks
    private SipRegistrationSecurityEslEventHandler handler;

    @Test
    void shouldMapRegisterFailureToSecurityEvent() {
        when(nodeQueryService.findTenantId(8L)).thenReturn("000000");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Event-Subclass", "sofia::register_failure");
        headers.put("Event-Date-Timestamp", "1791878400123456");
        headers.put("to-user", "1001");
        headers.put("network-ip", "192.0.2.10");
        headers.put("network-port", "5060");
        headers.put("network-protocol", "udp");
        headers.put("user-agent", "TestPhone/1.0");
        headers.put("call-id", "register-call-id");
        FreeSwitchEslEvent event = new FreeSwitchEslEvent(8L, "CUSTOM", headers);

        assertThat(handler.supports(event)).isTrue();
        handler.handle(event);

        ArgumentCaptor<SipRegistrationEventRequest> captor = ArgumentCaptor.forClass(SipRegistrationEventRequest.class);
        verify(securityService).record(captor.capture());
        SipRegistrationEventRequest request = captor.getValue();
        assertThat(request.getTenantId()).isEqualTo("000000");
        assertThat(request.getNodeId()).isEqualTo(8L);
        assertThat(request.getExtension()).isEqualTo("1001");
        assertThat(request.getSourceIp()).isEqualTo("192.0.2.10");
        assertThat(request.getSourcePort()).isEqualTo(5060);
        assertThat(request.getResult()).isEqualTo("FAILURE");
        assertThat(request.getReasonCode()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(request.getOccurredAt()).isBefore(LocalDateTime.now().plusSeconds(1));
        assertThat(request.getEventKey()).startsWith("esl-");
    }

    @Test
    void shouldMapSofiaRegistrationAliases() {
        when(nodeQueryService.findTenantId(8L)).thenReturn("000000");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Event-Subclass", "sofia::register");
        headers.put("Event-Date-Timestamp", "1791878400123456");
        headers.put("from-user", "1001");
        headers.put("sip_auth_username", "sec_1001");
        headers.put("network-ip", "192.0.2.10");
        headers.put("call-id", "register-success-call-id");
        FreeSwitchEslEvent event = new FreeSwitchEslEvent(8L, "CUSTOM", headers);

        handler.handle(event);

        ArgumentCaptor<SipRegistrationEventRequest> captor = ArgumentCaptor.forClass(SipRegistrationEventRequest.class);
        verify(securityService).record(captor.capture());
        assertThat(captor.getValue().getExtension()).isEqualTo("1001");
        assertThat(captor.getValue().getAuthUsername()).isEqualTo("sec_1001");
        assertThat(captor.getValue().getResult()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldIgnoreNonRegistrationCustomEvent() {
        FreeSwitchEslEvent event = new FreeSwitchEslEvent(8L, "CUSTOM", Map.of("Event-Subclass", "callcenter::call-coming"));

        assertThat(handler.supports(event)).isFalse();
        verify(securityService, never()).record(org.mockito.ArgumentMatchers.any());
    }
}
