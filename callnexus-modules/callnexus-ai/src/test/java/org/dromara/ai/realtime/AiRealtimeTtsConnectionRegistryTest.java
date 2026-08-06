package org.dromara.ai.realtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AiRealtimeTtsConnectionRegistryTest {

    @Test
    void cancelByCallIdShouldCancelEveryConnectionOnlyOnce() {
        AiRealtimeTtsConnectionRegistry registry = new AiRealtimeTtsConnectionRegistry();
        AtomicInteger cancelled = new AtomicInteger();

        registry.register("call-1", "turn-1", "session-1", cancelled::incrementAndGet);
        registry.register("call-1", "turn-1", "session-2", cancelled::incrementAndGet);

        assertThat(registry.cancelByCallId("call-1")).isEqualTo(2);
        assertThat(cancelled).hasValue(2);
        assertThat(registry.cancelByCallId("call-1")).isZero();
        assertThat(cancelled).hasValue(2);
    }

    @Test
    void unregisterShouldKeepOtherConnectionsForTheSameCall() {
        AiRealtimeTtsConnectionRegistry registry = new AiRealtimeTtsConnectionRegistry();
        AtomicInteger cancelled = new AtomicInteger();

        registry.register("call-1", "turn-1", "session-1", cancelled::incrementAndGet);
        registry.register("call-1", "turn-1", "session-2", cancelled::incrementAndGet);
        registry.unregister("call-1", "session-1");

        assertThat(registry.cancelByCallId("call-1")).isEqualTo(1);
        assertThat(cancelled).hasValue(1);
    }
}
