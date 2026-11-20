package org.dromara.license;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
@Tag("prod")
class LicenseFeatureGateTest {
    private final LicenseManagementService management = mock(LicenseManagementService.class);
    private final LicenseFeatureGate gate = new LicenseFeatureGate(management);

    @Test
    void deniesAbsentExpiredInvalidAndNotYetValidEvenWithFeatureBits() {
        for (String state : new String[]{"UNLICENSED", "EXPIRED", "INVALID", "NOT_YET_VALID"}) {
            when(management.status()).thenReturn(status(state, Map.of("auto_outbound", true, "ai_voice", true)));
            assertNotNull(gate.denialForAiVoice(), state);
            assertThrows(ServiceException.class, () -> gate.requireOutbound(false), state);
        }
    }

    @Test
    void distinguishesIvrOutboundAiOutboundAndAiInbound() {
        when(management.status()).thenReturn(status("VALID", Map.of("auto_outbound", true, "ai_voice", false)));
        assertNull(gate.denialForOutbound(false));
        assertNotNull(gate.denialForOutbound(true));
        assertNotNull(gate.denialForAiVoice());

        when(management.status()).thenReturn(status("VALID", Map.of("ai_voice", true)));
        assertNotNull(gate.denialForOutbound(false));
        assertNull(gate.denialForAiVoice());

        when(management.status()).thenReturn(status("VALID", Map.of("auto_outbound", true, "ai_voice", true)));
        assertNull(gate.denialForOutbound(true));
    }

    @Test
    void doesNotFailOpenIfTheLicenseDatabaseIsUnavailable() {
        when(management.status()).thenThrow(new DataAccessResourceFailureException("offline"));
        assertNotNull(gate.denialForAiVoice());
        assertNotNull(gate.denialForOutbound(false));
    }

    private static LicenseManagementService.Status status(String state, Map<String, Boolean> features) {
        return new LicenseManagementService.Status(state, null, null, true, null, null, null, null, features, Map.of(), null);
    }
}
