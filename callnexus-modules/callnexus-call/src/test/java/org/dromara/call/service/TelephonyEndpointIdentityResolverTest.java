package org.dromara.call.service;

import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.TelephonyEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class TelephonyEndpointIdentityResolverTest {

    private final SipBusinessIdentityResolver sipIdentityResolver = mock(SipBusinessIdentityResolver.class);
    private final TelephonyEndpointIdentityResolver resolver =
        new TelephonyEndpointIdentityResolver(sipIdentityResolver);

    @Test
    void shouldResolveWebRtcAuthIdentityToBusinessExtension() {
        Long nodeId = 1L;
        when(sipIdentityResolver.resolveExtension(nodeId, "cnx_random_identity")).thenReturn("1001");

        TelephonyEvent event = event(nodeId, Map.of(
            EslHeaders.CHANNEL_NAME, "sofia/internal/cnx_random_identity@example.com"
        ));

        assertEquals("1001", resolver.resolveAuthoritativeExtension(event));
    }

    @Test
    void shouldKeepTraditionalSipExtensionResolution() {
        Long nodeId = 1L;
        when(sipIdentityResolver.resolveExtension(nodeId, "1002")).thenReturn("1002");

        TelephonyEvent event = event(nodeId, Map.of(
            EslHeaders.VARIABLE_DIALED_USER, "1002"
        ));

        assertEquals("1002", resolver.resolveAuthoritativeExtension(event));
    }

    private TelephonyEvent event(Long nodeId, Map<String, String> headers) {
        return new TelephonyEvent(nodeId, "CHANNEL_ANSWER", "uuid", null, null, null, headers);
    }
}
