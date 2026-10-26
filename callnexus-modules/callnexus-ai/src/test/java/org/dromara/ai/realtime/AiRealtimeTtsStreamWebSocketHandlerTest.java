package org.dromara.ai.realtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiRealtimeTtsStreamWebSocketHandlerTest {

    @Test
    void shouldSplitHttpFallbackAtNaturalSentenceBoundaries() {
        String text = "那等您办理了入院手续再来电申请。感谢您的接听，祝您早日康复。请您先挂机，再见！";

        assertEquals(List.of(
            "那等您办理了入院手续再来电申请。",
            "感谢您的接听，祝您早日康复。",
            "请您先挂机，再见！"
        ), AiRealtimeTtsStreamWebSocketHandler.splitHttpFallbackText(text));
    }
}
