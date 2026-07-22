package org.dromara.ai.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRealtimeMrcpEventServiceTest {

    @Test
    void shouldMergeFollowupSegmentsWhileCurrentSegmentIsPlaying() {
        Deque<String> pending = new ArrayDeque<>();
        pending.add("如果您还想了解专业学费，");

        AiRealtimeMrcpEventService.enqueuePendingSegment(pending, "欢迎随时联系我们。", true);
        AiRealtimeMrcpEventService.enqueuePendingSegment(pending, "祝您生活愉快！", true);

        assertEquals(1, pending.size());
        assertEquals("如果您还想了解专业学费，欢迎随时联系我们。祝您生活愉快！", pending.peekFirst());
    }

    @Test
    void shouldNotMergeSegmentsWhenNothingIsPlaying() {
        Deque<String> pending = new ArrayDeque<>();
        pending.add("第一段。");

        AiRealtimeMrcpEventService.enqueuePendingSegment(pending, "第二段。", false);

        assertEquals(2, pending.size());
    }

    @Test
    void shouldRejectLateCompletionFromPreviousSegment() {
        String first = "第一段已经播放完成。";
        String second = "当前正在播放第二段。";
        String lateEvent = "unimrcp:callnexus-mrcp-v2:uuid|Serena|" + first;
        String currentEvent = "unimrcp:callnexus-mrcp-v2:uuid|Serena|" + second;

        assertFalse(AiRealtimeMrcpEventService.matchesSpeakCompletion(second, lateEvent));
        assertFalse(AiRealtimeMrcpEventService.matchesSpeakCompletion(second, null));
        assertTrue(AiRealtimeMrcpEventService.matchesSpeakCompletion(second, currentEvent));
    }
}
