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

    @Test
    void shouldBoundWebSocketCompletionFallbackWithoutCuttingLongSpeech() {
        assertEquals(5000L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.WS, 4380L, 5000L));
        assertEquals(15000L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.WS, 4380L, 180000L));
        assertEquals(42000L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.WS, 42000L, 5000L));
        assertEquals(4380L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.HTTP, 4380L, 180000L));
    }

    @Test
    void shouldLeaveConservativeFallbackForLongFixedTemplate() {
        String text = "您好，我是支付宝个人百万医疗保险的智能客服，现与您同步一下理赔案件进度："
            + "由于本次是首次申请理赔，且被保人申请的疾病达到重疾标准（或申请费用较高），"
            + "我们需要核实相关保险责任。因此，近期将安排工作人员进行线下面访核实，请您留意接听来电。 "
            + "核实完成后，我们会第一时间处理您的案件。感谢您的配合！";

        assertEquals(36160L, AiRealtimeMrcpEventService.estimateSpeakDelay(text));
    }

    @Test
    void shouldBoundRealtimeIntentRecognitionBudget() {
        assertEquals(1800L, AiRealtimeMrcpEventService.resolveRealtimeIntentTimeout(null));
        assertEquals(500L, AiRealtimeMrcpEventService.resolveRealtimeIntentTimeout(100L));
        assertEquals(1500L, AiRealtimeMrcpEventService.resolveRealtimeIntentTimeout(1500L));
        assertEquals(2000L, AiRealtimeMrcpEventService.resolveRealtimeIntentTimeout(5000L));
    }
}
