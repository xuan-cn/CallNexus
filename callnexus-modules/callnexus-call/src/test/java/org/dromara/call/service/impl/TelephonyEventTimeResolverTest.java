package org.dromara.call.service.impl;

import org.dromara.call.domain.TelephonyEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class TelephonyEventTimeResolverTest {

    @Test
    void shouldResolveFreeSwitchMicrosecondTimestamp() {
        long epochMillis = Instant.parse("2026-08-04T07:09:49Z").toEpochMilli();
        TelephonyEvent event = event(Map.of("Event-Date-Timestamp", Long.toString(epochMillis * 1000)));

        assertEquals(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()),
            TelephonyEventTimeResolver.resolve(event)
        );
    }

    @Test
    void shouldFallbackToFreeSwitchLocalTime() {
        TelephonyEvent event = event(Map.of("Event-Date-Local", "2026-08-04 15:09:49"));

        assertEquals(LocalDateTime.of(2026, 8, 4, 15, 9, 49), TelephonyEventTimeResolver.resolve(event));
    }

    private TelephonyEvent event(Map<String, String> headers) {
        return new TelephonyEvent(1L, "CHANNEL_HANGUP_COMPLETE", "uuid", "1001", "1002",
            "NORMAL_CLEARING", headers);
    }
}
