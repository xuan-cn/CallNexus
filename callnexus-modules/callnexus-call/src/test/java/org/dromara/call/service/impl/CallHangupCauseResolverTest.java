package org.dromara.call.service.impl;

import org.dromara.call.domain.CallLeg;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CallHangupCauseResolverTest {

    @Test
    void shouldPreserveFirstTerminalCauseAndTime() {
        LocalDateTime first = LocalDateTime.of(2026, 7, 21, 16, 0);
        LocalDateTime later = first.plusSeconds(8);

        assertEquals("NORMAL_CLEARING", CallHangupCauseResolver.preserveFirst("NORMAL_CLEARING", "MEDIA_TIMEOUT"));
        assertEquals(first, CallHangupCauseResolver.preserveFirst(first, later));
    }

    @Test
    void shouldPreferCustomerCauseOverLaterMediaLeg() {
        CallLeg customer = leg(1L, "CUSTOMER", "NORMAL_CLEARING", LocalDateTime.of(2026, 7, 21, 16, 0));
        CallLeg media = leg(2L, "UNKNOWN", "MEDIA_TIMEOUT", LocalDateTime.of(2026, 7, 21, 16, 0, 8));

        assertEquals("NORMAL_CLEARING",
            CallHangupCauseResolver.resolveSessionCause(List.of(media, customer), null, "MEDIA_TIMEOUT"));
    }

    private CallLeg leg(Long id, String role, String cause, LocalDateTime endedAt) {
        CallLeg leg = new CallLeg();
        leg.setId(id);
        leg.setLegRole(role);
        leg.setHangupCause(cause);
        leg.setEndedAt(endedAt);
        return leg;
    }
}
