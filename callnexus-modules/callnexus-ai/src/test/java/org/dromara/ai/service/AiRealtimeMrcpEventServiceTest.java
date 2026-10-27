package org.dromara.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class AiRealtimeMrcpEventServiceTest {

    @Test
    void shouldUseCompletedApplicationInsteadOfConcurrentRecognitionChannelVariables() {
        var application = AiRealtimeMrcpEventService.resolveEventApplication(Map.of(
            "Application", "speak", "Application-Data", "unimrcp:profile|voice|opening",
            "variable_current_application", "detect_speech",
            "variable_current_application_data", "unimrcp grammar"));
        assertEquals("speak", application.name());
        assertEquals("unimrcp:profile|voice|opening", application.data());
    }

    @Test
    void shouldNotCombineEventApplicationWithUnrelatedChannelApplicationData() {
        var application = AiRealtimeMrcpEventService.resolveEventApplication(Map.of(
            "Application", "speak", "variable_current_application_data", "unimrcp grammar"));
        assertEquals("speak", application.name());
        assertNull(application.data());
        var fallback = AiRealtimeMrcpEventService.resolveEventApplication(Map.of(
            "variable_current_application", "park", "variable_current_application_data", ""));
        assertEquals("park", fallback.name());
        assertNull(fallback.data());
    }

    @Test
    void shouldNotControlOrInterruptAfterPlaybackHasCompleted() {
        assertTrue(AiRealtimeMrcpEventService.hasInterruptiblePlayback(false, true, true, true));
        assertFalse(AiRealtimeMrcpEventService.hasInterruptiblePlayback(false, false, true, true));
        assertFalse(AiRealtimeMrcpEventService.hasInterruptiblePlayback(true, true, true, true));
        assertFalse(AiRealtimeMrcpEventService.hasInterruptiblePlayback(false, true, false, true));
        assertFalse(AiRealtimeMrcpEventService.hasInterruptiblePlayback(false, true, true, false));
    }

    @Test
    void shouldResolveNodeControlWithoutChangingAssistantDefaults() {
        var inherited = new AiRealtimeMrcpEventService.NodeBargeInPolicy(false, false,
            "INTERRUPT", "assistant text", 2, 5000);
        assertEquals(inherited, AiRealtimeMrcpEventService.resolveNodeBargeInPolicy(Map.of(), inherited));
        var node = AiRealtimeMrcpEventService.resolveNodeBargeInPolicy(Map.of(
            "nodeBargeInMode", "CONTROL_RESUME", "nodeControlText", "{{customer.name}} please wait",
            "nodeControlMaxCount", 3, "nodeControlCooldownMs", 1200), inherited);
        assertTrue(node.enabled());
        assertTrue(node.openingEnabled());
        assertEquals("CONTROL_RESUME", node.strategy());
        assertEquals("{{customer.name}} please wait", node.text());
        assertEquals(3, node.maxCount());
        assertEquals(1200, node.cooldownMs());
        assertFalse(inherited.enabled());
    }

    @Test
    void shouldDisableNodeOrUsePlainInterruptionAndClampLimits() {
        var inherited = new AiRealtimeMrcpEventService.NodeBargeInPolicy(true, true,
            "CONTROL_RESUME", "assistant text", 2, 5000);
        assertFalse(AiRealtimeMrcpEventService.resolveNodeBargeInPolicy(
            Map.of("nodeBargeInMode", "DISABLED"), inherited).enabled());
        var node = AiRealtimeMrcpEventService.resolveNodeBargeInPolicy(Map.of(
            "nodeBargeInMode", "INTERRUPT", "nodeControlText", "", "nodeControlMaxCount", -1,
            "nodeControlCooldownMs", 999999), inherited);
        assertEquals("INTERRUPT", node.strategy());
        assertEquals("assistant text", node.text());
        assertEquals(1, node.maxCount());
        assertEquals(60000, node.cooldownMs());
    }

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
        assertTrue(AiRealtimeMrcpEventService.matchesSpeakCompletion(second + " ", currentEvent));
    }

    @Test
    void shouldTreatEnteringParkAsOpeningPlaybackBoundary() {
        assertTrue(AiRealtimeMrcpEventService.isOpeningParkEntered("CHANNEL_EXECUTE", "park"));
        assertTrue(AiRealtimeMrcpEventService.isOpeningParkEntered("CHANNEL_EXECUTE", "PARK"));
        assertFalse(AiRealtimeMrcpEventService.isOpeningParkEntered("CHANNEL_EXECUTE_COMPLETE", "park"));
        assertFalse(AiRealtimeMrcpEventService.isOpeningParkEntered("CHANNEL_EXECUTE", "speak"));
    }

    @Test
    void shouldOnlyRecognizeDuringOpeningWhenOpeningBargeInIsEnabled() {
        assertFalse(AiRealtimeMrcpEventService.shouldStartRecognitionDuringPlayback(true, false, false, true));
        assertFalse(AiRealtimeMrcpEventService.shouldStartRecognitionDuringPlayback(true, true, false, true));
        assertTrue(AiRealtimeMrcpEventService.shouldStartRecognitionDuringPlayback(true, true, true, true));
        assertFalse(AiRealtimeMrcpEventService.shouldStartRecognitionDuringPlayback(true, true, true, false));
    }

    @Test
    void shouldOnlyListenDuringOrdinaryPlaybackWhenBargeInIsEnabled() {
        assertTrue(AiRealtimeMrcpEventService.shouldStartRecognitionDuringPlayback(false, true, false, true));
        assertFalse(AiRealtimeMrcpEventService.shouldStartRecognitionDuringPlayback(false, false, true, true));
    }

    @Test
    void shouldMatchAudioReadyToCurrentSpeechSegment() {
        AiRealtimeMrcpEventService.ActiveSpeak active = new AiRealtimeMrcpEventService.ActiveSpeak(
            "turn-2", 3, "您好", 1L, 1L, false, false);

        assertTrue(AiRealtimeMrcpEventService.matchesAudioReady(active, "turn-2", 3));
        assertFalse(AiRealtimeMrcpEventService.matchesAudioReady(active, "turn-1", 3));
        assertFalse(AiRealtimeMrcpEventService.matchesAudioReady(active, "turn-2", 2));
    }

    @Test
    void shouldFallbackOnlyWhenWebSocketCompletesBeforeAudioIsReady() {
        assertTrue(AiRealtimeMrcpEventService.shouldFallbackBeforeCompleting(VoiceTransport.WS, false));
        assertFalse(AiRealtimeMrcpEventService.shouldFallbackBeforeCompleting(VoiceTransport.WS, true));
        assertFalse(AiRealtimeMrcpEventService.shouldFallbackBeforeCompleting(VoiceTransport.HTTP, false));
    }

    @Test
    void shouldAcceptFirstSpeakCompletionWithoutRelyingOnApplicationDataFormat() {
        AiRealtimeMrcpEventService.ActiveSpeak opening = new AiRealtimeMrcpEventService.ActiveSpeak(
            "opening", 1, "开场白", 1L, 1L, true, false);
        AiRealtimeMrcpEventService.ActiveSpeak reply = new AiRealtimeMrcpEventService.ActiveSpeak(
            "turn-2", 2, "当前回复", 1L, 2L, false, false);

        assertTrue(AiRealtimeMrcpEventService.shouldAcceptSpeakCompletion(opening, null, null, null, null));
        assertFalse(AiRealtimeMrcpEventService.shouldAcceptSpeakCompletion(opening, null, null, null, "unexpected"));
        assertFalse(AiRealtimeMrcpEventService.shouldAcceptSpeakCompletion(reply, null, null, null, "previous"));
    }

    @Test
    void shouldCorrelateSpeakCompletionByTurnSegmentAndGeneration() {
        AiRealtimeMrcpEventService.ActiveSpeak active = new AiRealtimeMrcpEventService.ActiveSpeak(
            "turn-7", 3, "当前回复", 1L, 9L, false, false);

        assertTrue(AiRealtimeMrcpEventService.shouldAcceptSpeakCompletion(
            active, "turn-7", 3, 9L, null));
        assertFalse(AiRealtimeMrcpEventService.shouldAcceptSpeakCompletion(
            active, "turn-6", 3, 9L, "当前回复"));
        assertFalse(AiRealtimeMrcpEventService.shouldAcceptSpeakCompletion(
            active, "turn-7", 2, 9L, "当前回复"));
        assertFalse(AiRealtimeMrcpEventService.shouldAcceptSpeakCompletion(
            active, "turn-7", 3, 8L, "当前回复"));
        assertTrue(AiRealtimeMrcpEventService.shouldAcceptSpeakCompletion(
            active, "turn-7", 3, 9L, "上一轮回复"));
    }

    @Test
    void shouldIdentifyDuplicateRecognitionByEventInsteadOfText() {
        assertEquals("sequence:17", AiRealtimeMrcpEventService.recognitionEventKey(
            Map.of("Event-Sequence", "17", "CallNexus-Event-Body", "没有。")));
        assertEquals("sequence:18", AiRealtimeMrcpEventService.recognitionEventKey(
            Map.of("Event-Sequence", "18", "CallNexus-Event-Body", "没有。")));
        assertEquals("timestamp:1792030536483100:channel:asr-1", AiRealtimeMrcpEventService.recognitionEventKey(
            Map.of("Event-Date-Timestamp", "1792030536483100", "Channel-Identifier", "asr-1")));
        assertNull(AiRealtimeMrcpEventService.recognitionEventKey(Map.of()));
    }

    @Test
    void shouldBoundWebSocketCompletionFallbackWithoutCuttingLongSpeech() {
        assertEquals(12000L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.WS, 4380L, 5000L));
        assertEquals(15000L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.WS, 4380L, 180000L));
        assertEquals(63000L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.WS, 42000L, 5000L));
        assertEquals(9380L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.HTTP, 4380L, 180000L));
        assertEquals(32580L,
            AiRealtimeMrcpEventService.resolveSpeakCompletionTimeout(VoiceTransport.WS, 21720L, 12000L));
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
    void shouldCalculatePlaybackWatchdogFromPcmDuration() {
        assertEquals(1000L, AiRealtimeMrcpEventService.pcmDurationMillis(16000L, 8000));
        assertEquals(30000L,
            AiRealtimeMrcpEventService.resolvePlaybackCompletionTimeout(16000L, 8000, 30000L));
        assertEquals(90000L,
            AiRealtimeMrcpEventService.resolvePlaybackCompletionTimeout(960000L, 8000, 30000L));
    }

    @Test
    void shouldBoundRealtimeIntentRecognitionBudget() {
        assertEquals(1800L, AiRealtimeMrcpEventService.resolveRealtimeIntentTimeout(null));
        assertEquals(500L, AiRealtimeMrcpEventService.resolveRealtimeIntentTimeout(100L));
        assertEquals(1500L, AiRealtimeMrcpEventService.resolveRealtimeIntentTimeout(1500L));
        assertEquals(2000L, AiRealtimeMrcpEventService.resolveRealtimeIntentTimeout(5000L));
    }

    @Test
    void shouldDiscardAllRemainingSpeechAndInvalidateOldModelStreamOnControlReplacement() {
        Deque<String> pending = new ArrayDeque<>();
        pending.add("被打断的当前句。");
        pending.add("后续句子。");
        java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong(3L);

        AiRealtimeMrcpEventService.discardReplacedSpeech(pending, generation);

        assertTrue(pending.isEmpty());
        assertEquals(4L, generation.get());
    }

    @Test
    void shouldEnforceControlCountAndCooldown() {
        long now = 10_000_000_000L;
        long cooldown = 5_000_000_000L;

        assertTrue(AiRealtimeMrcpEventService.controlBargeInAvailable(0, 2, now, 0L, cooldown));
        assertFalse(AiRealtimeMrcpEventService.controlBargeInAvailable(2, 2, now, 0L, cooldown));
        assertFalse(AiRealtimeMrcpEventService.controlBargeInAvailable(1, 2, now, now - cooldown + 1L, cooldown));
        assertTrue(AiRealtimeMrcpEventService.controlBargeInAvailable(1, 2, now, now - cooldown, cooldown));
    }

    @Test
    void shouldKeepLatestRecognitionUntilRuntimeIsReady() {
        AiRealtimeMrcpEventService.DeferredRecognitionSlot slot =
            new AiRealtimeMrcpEventService.DeferredRecognitionSlot();
        LocalDateTime firstAt = LocalDateTime.of(2026, 10, 21, 15, 45, 33);
        LocalDateTime secondAt = firstAt.plusSeconds(12);

        slot.offer(new AiRealtimeMrcpEventService.DeferredRecognition("生病。", 100L, firstAt, true));
        slot.offer(new AiRealtimeMrcpEventService.DeferredRecognition("眼科。", 200L, secondAt, true));

        AiRealtimeMrcpEventService.DeferredRecognition pending = slot.poll();
        assertEquals("眼科。", pending.text());
        assertEquals(200L, pending.receivedAtNanos());
        assertEquals(secondAt, pending.recognizedAt());
        assertTrue(pending.transcriptPersisted());
        assertNull(slot.poll());
    }

    @Test
    void shouldClearDeferredRecognitionWhenCallEnds() {
        AiRealtimeMrcpEventService.DeferredRecognitionSlot slot =
            new AiRealtimeMrcpEventService.DeferredRecognitionSlot();
        slot.offer(new AiRealtimeMrcpEventService.DeferredRecognition(
            "需要。", 100L, LocalDateTime.now(), false));

        slot.clear();

        assertNull(slot.poll());
    }
}
