package org.dromara.call.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallRealtimeAsrControlServiceTest {

    @Test
    void shouldYieldWhenAnyAiRealtimeOwnershipSignalExists() {
        assertTrue(CallRealtimeAsrControlService.shouldYieldToAiRealtime(true, false, false));
        assertTrue(CallRealtimeAsrControlService.shouldYieldToAiRealtime(false, true, false));
        assertTrue(CallRealtimeAsrControlService.shouldYieldToAiRealtime(false, false, true));
    }

    @Test
    void shouldKeepGenericRecognitionForOrdinaryCalls() {
        assertFalse(CallRealtimeAsrControlService.shouldYieldToAiRealtime(false, false, false));
    }
}
