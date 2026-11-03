package org.dromara.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.dromara.ai.domain.AiRealtimeCallSession;
import org.dromara.ai.domain.AiRealtimeCallTurn;
import org.dromara.ai.mapper.AiRealtimeCallTurnMapper;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiPlaybackOwnershipTest {
    private AiRealtimeMrcpEventService.ActiveSpeak speech(int sequence) {
        return new AiRealtimeMrcpEventService.ActiveSpeak("turn-1", sequence, "您好", 1L, sequence, false, false);
    }

    @Test
    void forbiddenInterruptionPreservesPlaybackAndItsCompletionOwnership() {
        var active = speech(1);
        var playback = new AtomicReference<>(active);
        assertFalse(AiRealtimeMrcpEventService.claimInterruptibleSpeak(playback, active, false));
        assertSame(active, playback.get());
        assertTrue(playback.compareAndSet(active, null));
    }

    @Test
    void staleInterruptionDoesNotRemoveReplacementPlayback() {
        var old = speech(1);
        var replacement = speech(2);
        var playback = new AtomicReference<>(replacement);
        assertFalse(AiRealtimeMrcpEventService.claimInterruptibleSpeak(playback, old, true));
        assertSame(replacement, playback.get());
    }

    @Test
    void interruptedPlaybackCannotCompleteOrBeInterruptedTwice() {
        var active = speech(1);
        var playback = new AtomicReference<>(active);
        assertTrue(AiRealtimeMrcpEventService.claimInterruptibleSpeak(playback, active, true));
        assertFalse(playback.compareAndSet(active, null));
        assertFalse(AiRealtimeMrcpEventService.claimInterruptibleSpeak(playback, active, true));
        assertFalse(AiRealtimeMrcpEventService.claimInterruptibleSpeak(playback, null, true));
    }

    @Test
    void concurrentInterruptionHasOnlyOneWinnerAndOtherCallsAreUntouched() throws Exception {
        var active = speech(1);
        var playback = new AtomicReference<>(active);
        var otherCall = new AtomicReference<>(speech(2));
        var otherActive = otherCall.get();
        var workers = Executors.newFixedThreadPool(5);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                results.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return AiRealtimeMrcpEventService.claimInterruptibleSpeak(playback, active, true);
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertSame(otherActive, otherCall.get());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidatedCompletionCannotDetachPlaybackOrAdvanceWorkflow() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var active = speech(1);
        field(type, runtime, "activeSpeak", AtomicReference.class).set(active);
        field(type, runtime, "turnGeneration", AtomicLong.class).set(2L);
        field(type, runtime, "workflowActive", AtomicBoolean.class).set(true);
        field(type, runtime, "workflowSpeak", AtomicBoolean.class).set(true);
        var service = mock(AiRealtimeMrcpEventService.class, CALLS_REAL_METHODS);
        var workflow = mock(AiWorkflowRuntimeService.class);
        var dependency = AiRealtimeMrcpEventService.class.getDeclaredField("workflowRuntimeService");
        dependency.setAccessible(true);
        dependency.set(service, workflow);
        var completion = AiRealtimeMrcpEventService.class.getDeclaredMethod("completeSpeak", type,
            AiRealtimeMrcpEventService.ActiveSpeak.class, String.class);
        completion.setAccessible(true);
        completion.invoke(service, runtime, active, "TEST");
        var continuation = AiRealtimeMrcpEventService.class.getDeclaredMethod("continueAfterSpeak", type,
            AiRealtimeMrcpEventService.ActiveSpeak.class);
        continuation.setAccessible(true);
        continuation.invoke(service, runtime, active);
        assertSame(active, field(type, runtime, "activeSpeak", AtomicReference.class).get());
        assertTrue(field(type, runtime, "workflowSpeak", AtomicBoolean.class).get());
        verifyNoInteractions(workflow);
    }

    @Test
    void staleTimeoutRegistrationDoesNotCancelNewPlaybackTimer() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var replacement = speech(2);
        var timer = mock(ScheduledFuture.class);
        field(type, runtime, "activeSpeak", AtomicReference.class).set(replacement);
        field(type, runtime, "pendingSpeakTimer", AtomicReference.class).set(timer);
        var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("scheduleSpeakTimeout", type,
            AiRealtimeMrcpEventService.ActiveSpeak.class, long.class, String.class);
        method.setAccessible(true);
        method.invoke(mock(AiRealtimeMrcpEventService.class, CALLS_REAL_METHODS), runtime, speech(1), 1000L, "STALE");
        assertSame(timer, field(type, runtime, "pendingSpeakTimer", AtomicReference.class).get());
        verifyNoInteractions(timer);
    }

    @Test
    void completionAndTimeoutShareOneTransitionOwner() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var active = speech(1);
        var timer = mock(ScheduledFuture.class);
        field(type, runtime, "activeSpeak", AtomicReference.class).set(active);
        field(type, runtime, "turnGeneration", AtomicLong.class).set(1L);
        field(type, runtime, "waitingSpeakComplete", AtomicBoolean.class).set(true);
        field(type, runtime, "pendingSpeakTimer", AtomicReference.class).set(timer);
        var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("claimPlaybackTransition", type,
            AiRealtimeMrcpEventService.ActiveSpeak.class, boolean.class);
        method.setAccessible(true);
        var workers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Boolean> completed = workers.submit(() -> { start.await(); return (Boolean) method.invoke(null, runtime, active, false); });
            Future<Boolean> timedOut = workers.submit(() -> { start.await(); return (Boolean) method.invoke(null, runtime, active, true); });
            start.countDown();
            boolean completedWon = completed.get(5, TimeUnit.SECONDS);
            boolean timeoutWon = timedOut.get(5, TimeUnit.SECONDS);
            assertNotEquals(completedWon, timeoutWon);
            assertEquals(timeoutWon ? 2L : 1L, field(type, runtime, "turnGeneration", AtomicLong.class).get());
            assertFalse(field(type, runtime, "waitingSpeakComplete", AtomicBoolean.class).get());
            assertNull(field(type, runtime, "activeSpeak", AtomicReference.class).get());
            verify(timer, times(1)).cancel(false);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Object newRuntime(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance("000000", 1L, 2L, "call", "uuid", "agent", "voice",
            new AiRealtimeCallSession(), false, VoiceTransport.HTTP, null, true, true,
            "STANDARD", 0, "INTERRUPT", "control", 2, 5000);
    }

    @Test
    void staleModelFailureCannotDetachNewTurnOrCancelItsTimer() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var turn = new AiRealtimeCallTurn();
        var active = speech(2);
        var timer = mock(ScheduledFuture.class);
        field(type, runtime, "turnGeneration", AtomicLong.class).set(2L);
        field(type, runtime, "currentTurn", AtomicReference.class).set(turn);
        field(type, runtime, "activeSpeak", AtomicReference.class).set(active);
        field(type, runtime, "pendingSpeakTimer", AtomicReference.class).set(timer);
        assertNull(detachFailure(type, runtime, 1L, turn));
        assertNull(detachFailure(type, runtime, 2L, new AiRealtimeCallTurn()));
        assertSame(active, field(type, runtime, "activeSpeak", AtomicReference.class).get());
        assertSame(turn, field(type, runtime, "currentTurn", AtomicReference.class).get());
        verifyNoInteractions(timer);
    }

    @Test
    void currentFailureInvalidatesOnceAndDefersInputUntilCleanupFinishes() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var turn = new AiRealtimeCallTurn();
        var timer = mock(ScheduledFuture.class);
        field(type, runtime, "turnGeneration", AtomicLong.class).set(1L);
        field(type, runtime, "currentTurn", AtomicReference.class).set(turn);
        field(type, runtime, "activeSpeak", AtomicReference.class).set(speech(1));
        field(type, runtime, "pendingSpeakTimer", AtomicReference.class).set(timer);
        field(type, runtime, "waitingSpeakComplete", AtomicBoolean.class).set(true);
        assertNotNull(detachFailure(type, runtime, 1L, turn));
        assertNull(detachFailure(type, runtime, 1L, turn));
        assertEquals(2L, field(type, runtime, "turnGeneration", AtomicLong.class).get());
        assertTrue(field(type, runtime, "turnInProgress", AtomicBoolean.class).get());
        assertFalse(field(type, runtime, "waitingSpeakComplete", AtomicBoolean.class).get());
        assertNull(field(type, runtime, "activeSpeak", AtomicReference.class).get());
        assertNull(field(type, runtime, "currentTurn", AtomicReference.class).get());
        verify(timer, times(1)).cancel(false);
    }

    @Test
    void failureCannotReclaimClosedSession() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        field(type, runtime, "closed", AtomicBoolean.class).set(true);
        assertNull(detachFailure(type, runtime, 0L, null));
        assertFalse(field(type, runtime, "turnInProgress", AtomicBoolean.class).get());
    }

    private Object detachFailure(Class<?> type, Object runtime, long generation, AiRealtimeCallTurn turn) throws Exception {
        var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("detachFailedPlayback", type, long.class, AiRealtimeCallTurn.class);
        method.setAccessible(true);
        return method.invoke(null, runtime, generation, turn);
    }

    @Test
    void failedTurnPersistenceDoesNotBlockReturnToListening() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var turn = new AiRealtimeCallTurn();
        field(type, runtime, "currentTurn", AtomicReference.class).set(turn);
        Object failed = detachFailure(type, runtime, 0L, turn);
        // Recognition is already running; no telephony gateway is needed for this recovery test.
        field(type, runtime, "recognizing", AtomicBoolean.class).set(true);
        var service = mock(AiRealtimeMrcpEventService.class, CALLS_REAL_METHODS);
        var turnMapper = mock(AiRealtimeCallTurnMapper.class);
        doThrow(new IllegalStateException("database unavailable")).when(turnMapper).updateById(any(AiRealtimeCallTurn.class));
        var mapperField = AiRealtimeMrcpEventService.class.getDeclaredField("turnMapper");
        mapperField.setAccessible(true);
        mapperField.set(service, turnMapper);
        var sessionField = AiRealtimeMrcpEventService.class.getDeclaredField("sessionMapper");
        sessionField.setAccessible(true);
        sessionField.set(service, mock(AiRealtimeCallSessionMapper.class));
        var recovery = AiRealtimeMrcpEventService.class.getDeclaredMethod("recoverFailedPlayback", type, failed.getClass(), String.class);
        recovery.setAccessible(true);
        recovery.invoke(service, runtime, failed, "model failed");
        assertFalse(field(type, runtime, "turnInProgress", AtomicBoolean.class).get());
        assertEquals("LISTENING", field(type, runtime, "entity", AiRealtimeCallSession.class).getSessionState());
        assertEquals("FAILED", turn.getTurnState());
    }

    @Test
    void lateParkCannotClearNewPlaybackOrItsTimer() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var next = speech(2);
        var timer = mock(ScheduledFuture.class);
        field(type, runtime, "conversationReady", AtomicBoolean.class).set(true);
        field(type, runtime, "preplayedOpeningReleased", AtomicBoolean.class).set(true);
        field(type, runtime, "activeSpeak", AtomicReference.class).set(next);
        field(type, runtime, "waitingSpeakComplete", AtomicBoolean.class).set(true);
        field(type, runtime, "pendingSpeakTimer", AtomicReference.class).set(timer);
        invokePark(type, runtime);
        assertSame(next, field(type, runtime, "activeSpeak", AtomicReference.class).get());
        assertTrue(field(type, runtime, "waitingSpeakComplete", AtomicBoolean.class).get());
        assertSame(timer, field(type, runtime, "pendingSpeakTimer", AtomicReference.class).get());
        verifyNoInteractions(timer);
    }

    @Test
    void earlyParkOnlyRemembersCompletionUntilOpeningIsRegistered() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        invokePark(type, runtime);
        assertTrue(field(type, runtime, "preplayedOpeningCompleted", AtomicBoolean.class).get());
        assertFalse(field(type, runtime, "preplayedOpeningReleased", AtomicBoolean.class).get());
        assertNull(field(type, runtime, "activeSpeak", AtomicReference.class).get());
    }

    @Test
    void closingPreplayedOperationReleasesItsBoundaryOnlyOnce() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var opening = speech(1);
        field(type, runtime, "activeSpeak", AtomicReference.class).set(opening);
        field(type, runtime, "preplayedOpeningSpeak", AtomicReference.class).set(opening);
        field(type, runtime, "turnGeneration", AtomicLong.class).set(1L);
        var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("claimPlaybackTransition", type,
            AiRealtimeMrcpEventService.ActiveSpeak.class, boolean.class);
        method.setAccessible(true);
        assertEquals(true, method.invoke(null, runtime, opening, false));
        assertEquals(false, method.invoke(null, runtime, opening, false));
        assertTrue(field(type, runtime, "preplayedOpeningReleased", AtomicBoolean.class).get());
        assertNull(field(type, runtime, "preplayedOpeningSpeak", AtomicReference.class).get());
    }

    private void invokePark(Class<?> type, Object runtime) throws Exception {
        var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("completePreplayedOpeningOnPark", type);
        method.setAccessible(true);
        method.invoke(mock(AiRealtimeMrcpEventService.class, CALLS_REAL_METHODS), runtime);
    }

    @Test
    void stopSuccessDoesNotTriggerFailureDisposition() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var gateway = mock(AiRealtimeTelephonyGateway.class);
        when(gateway.callExists(1L, "uuid")).thenReturn(true);
        var service = stopService(gateway);
        var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("stopPlaybackSafely", type);
        method.setAccessible(true);
        assertEquals(true, method.invoke(service, runtime));
        verify(gateway).stopPlayback(1L, "uuid");
        verify(gateway, never()).hangup(any(), any());
        verify(gateway, never()).transferToQueue(any(), any(), any());
    }

    @Test
    void unknownChannelAfterThreeFailedStopsDoesNotBlindlyTransferOrHangup() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var gateway = mock(AiRealtimeTelephonyGateway.class);
        when(gateway.callExists(1L, "uuid")).thenReturn(true, true, true).thenThrow(new IllegalStateException("unreachable"));
        doThrow(new IllegalStateException("stop timed out")).when(gateway).stopPlayback(1L, "uuid");
        var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("stopPlaybackSafely", type);
        method.setAccessible(true);
        assertEquals(false, method.invoke(stopService(gateway), runtime));
        verify(gateway, times(3)).stopPlayback(1L, "uuid");
        verify(gateway, never()).hangup(any(), any());
        verify(gateway, never()).transferToQueue(any(), any(), any());
        assertEquals("FAILED", field(type, runtime, "entity", AiRealtimeCallSession.class).getSessionState());
    }

    private AiRealtimeMrcpEventService stopService(AiRealtimeTelephonyGateway gateway) throws Exception {
        var service = mock(AiRealtimeMrcpEventService.class, CALLS_REAL_METHODS);
        ObjectProvider<AiRealtimeTelephonyGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(gateway);
        var providerField = AiRealtimeMrcpEventService.class.getDeclaredField("telephonyGatewayProvider");
        providerField.setAccessible(true);
        providerField.set(service, provider);
        var mapperField = AiRealtimeMrcpEventService.class.getDeclaredField("sessionMapper");
        mapperField.setAccessible(true);
        mapperField.set(service, mock(AiRealtimeCallSessionMapper.class));
        var propertiesField = AiRealtimeMrcpEventService.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        propertiesField.set(service, new org.dromara.ai.config.AiKnowledgeProperties());
        return service;
    }

    @Test
    void selectedFailureActionIsAttemptedOnceOnlyAfterBoundedRetries() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        for (String action : List.of("HANGUP", "TRANSFER_QUEUE")) {
            Object runtime = newRuntime(type);
            var config = type.getDeclaredField("playbackFailureConfig");
            config.setAccessible(true);
            config.set(runtime, java.util.Map.of("playbackFailureAction", action, "playbackFailureQueueCode", "support"));
            var gateway = mock(AiRealtimeTelephonyGateway.class);
            when(gateway.callExists(1L, "uuid")).thenReturn(true);
            doThrow(new IllegalStateException("stop failed")).when(gateway).stopPlayback(1L, "uuid");
            // Simulate an action submission failure; it must not cause repeated transfer/hangup attempts.
            doThrow(new IllegalStateException("action unavailable")).when(gateway).hangup(1L, "uuid");
            doThrow(new IllegalStateException("action unavailable")).when(gateway).transferToQueue(1L, "uuid", "support");
            var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("stopPlaybackSafely", type);
            method.setAccessible(true);
            var service = stopService(gateway);
            assertEquals(false, method.invoke(service, runtime));
            assertEquals(false, method.invoke(service, runtime));
            verify(gateway, times(3)).stopPlayback(1L, "uuid");
            verify(gateway, times(action.equals("HANGUP") ? 1 : 0)).hangup(1L, "uuid");
            verify(gateway, times(action.equals("TRANSFER_QUEUE") ? 1 : 0)).transferToQueue(1L, "uuid", "support");
        }
    }

    private <T> T field(Class<?> type, Object target, String name, Class<T> valueType) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return valueType.cast(field.get(target));
    }

    @Test
    void stopIntentFailureDoesNotFallThroughToListening() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var gateway = mock(AiRealtimeTelephonyGateway.class);
        when(gateway.callExists(1L, "uuid")).thenReturn(true, true, true, true).thenThrow(new IllegalStateException("unknown"));
        doThrow(new IllegalStateException("stop failed")).when(gateway).stopPlayback(1L, "uuid");
        Class<?> actionType = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$PendingIntentAction");
        var constructor = actionType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object action = constructor.newInstance("stop", "stop", "STOP_PLAYBACK", "", "", "{}");
        var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("executeIntentAction", type, actionType);
        method.setAccessible(true);
        assertEquals(true, method.invoke(stopService(gateway), runtime, action));
        verify(gateway, times(3)).stopPlayback(1L, "uuid");
        verify(gateway, never()).hangup(any(), any());
        verify(gateway, never()).transferToQueue(any(), any(), any());
        assertEquals("FAILED", field(type, runtime, "entity", AiRealtimeCallSession.class).getSessionState());
    }

    @Test
    void hangupAndTransferSuccessCloseAiSessionWhileGoneChannelNeverTransfers() throws Exception {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("redissonClient", mock(org.redisson.api.RedissonClient.class));
            context.refresh();
            new org.dromara.common.core.utils.SpringUtils().setApplicationContext(context);
            try (var redis = mockStatic(org.dromara.common.redis.utils.RedisUtils.class)) {
                Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
                for (String scenario : List.of("GONE", "GONE_DURING_RETRY", "HANGUP", "TRANSFER_QUEUE")) {
                    Object runtime = newRuntime(type);
                    var gateway = mock(AiRealtimeTelephonyGateway.class);
                    if (scenario.equals("GONE")) when(gateway.callExists(1L, "uuid")).thenReturn(false);
                    else if (scenario.equals("GONE_DURING_RETRY")) when(gateway.callExists(1L, "uuid")).thenReturn(true, false);
                    else when(gateway.callExists(1L, "uuid")).thenReturn(true);
                    doThrow(new IllegalStateException("stop failed")).when(gateway).stopPlayback(1L, "uuid");
                    var config = type.getDeclaredField("playbackFailureConfig");
                    config.setAccessible(true);
                    config.set(runtime, java.util.Map.of("playbackFailureAction", scenario.equals("TRANSFER_QUEUE") ? "TRANSFER_QUEUE" : "HANGUP", "playbackFailureQueueCode", "support"));
                    var service = stopService(gateway);
                    setServiceField(service, "sessions", new java.util.concurrent.ConcurrentHashMap<>());
                    setServiceField(service, "instanceId", "test-instance");
                    setServiceField(service, "ttsConnectionRegistry", mock(org.dromara.ai.realtime.AiRealtimeTtsConnectionRegistry.class));
                    var method = AiRealtimeMrcpEventService.class.getDeclaredMethod("stopPlaybackSafely", type);
                    method.setAccessible(true);
                    assertEquals(false, method.invoke(service, runtime));
                    assertEquals(false, method.invoke(service, runtime));
                    assertTrue(field(type, runtime, "closed", AtomicBoolean.class).get());
                    assertEquals("ENDED", field(type, runtime, "entity", AiRealtimeCallSession.class).getSessionState());
                    verify(gateway, times(scenario.equals("TRANSFER_QUEUE") ? 1 : 0)).transferToQueue(1L, "uuid", "support");
                    verify(gateway, times(scenario.equals("HANGUP") ? 1 : 0)).hangup(1L, "uuid");
                    verify(gateway, times(scenario.equals("GONE") ? 0 : scenario.equals("GONE_DURING_RETRY") ? 1 : 3)).stopPlayback(1L, "uuid");
                }
            }
        }
    }

    private void setServiceField(AiRealtimeMrcpEventService service, String name, Object value) throws Exception {
        var field = AiRealtimeMrcpEventService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Test
    void failedSessionCannotSubmitSpeechOrContinueWorkflow() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        field(type, runtime, "entity", AiRealtimeCallSession.class).setSessionState("FAILED");
        field(type, runtime, "workflowActive", AtomicBoolean.class).set(true);
        field(type, runtime, "workflowSpeak", AtomicBoolean.class).set(true);
        var service = mock(AiRealtimeMrcpEventService.class, CALLS_REAL_METHODS);
        var speak = AiRealtimeMrcpEventService.class.getDeclaredMethod("speak", type, String.class,
            AiRealtimeCallTurn.class, boolean.class, boolean.class);
        speak.setAccessible(true);
        speak.invoke(service, runtime, "late reply", null, false, false);
        var continuation = AiRealtimeMrcpEventService.class.getDeclaredMethod("continueAfterSpeak", type,
            AiRealtimeMrcpEventService.ActiveSpeak.class);
        continuation.setAccessible(true);
        continuation.invoke(service, runtime, new AiRealtimeMrcpEventService.ActiveSpeak("old", 1, "old", 0L, 0L, false, false));
        assertTrue(field(type, runtime, "workflowSpeak", AtomicBoolean.class).get());
        assertNull(field(type, runtime, "activeSpeak", AtomicReference.class).get());
    }

    @Test
    void recognitionCannotBeAcceptedAfterFailureTransferOrClosure() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        var accept = type.getDeclaredMethod("acceptRecognition");
        accept.setAccessible(true);
        for (String state : List.of("FAILED", "ENDING", "ENDED", "TRANSFERRING")) {
            Object runtime = newRuntime(type);
            field(type, runtime, "entity", AiRealtimeCallSession.class).setSessionState(state);
            assertEquals(false, accept.invoke(runtime));
            assertFalse(field(type, runtime, "turnInProgress", AtomicBoolean.class).get());
        }
        Object runtime = newRuntime(type);
        field(type, runtime, "playbackFailureHandling", AtomicBoolean.class).set(true);
        assertEquals(false, accept.invoke(runtime));
        field(type, runtime, "playbackFailureHandling", AtomicBoolean.class).set(false);
        field(type, runtime, "closed", AtomicBoolean.class).set(true);
        assertEquals(false, accept.invoke(runtime));
    }

    @Test
    void workflowResponseCannotStartPlaybackAfterDispositionBegins() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var execution = type.getDeclaredField("workflowExecutionId");
        execution.setAccessible(true);
        execution.set(runtime, "execution");
        var workflow = mock(AiWorkflowRuntimeService.class);
        when(workflow.voiceInput(eq("execution"), anyString(), eq("yes"))).thenAnswer(invocation -> {
            field(type, runtime, "playbackFailureHandling", AtomicBoolean.class).set(true);
            var response = new org.dromara.ai.domain.response.AiWorkflowVoiceExecutionResponse();
            response.setActionType("SPEAK");
            response.setText("old reply");
            return response;
        });
        var service = mock(AiRealtimeMrcpEventService.class, CALLS_REAL_METHODS);
        setServiceField(service, "workflowRuntimeService", workflow);
        var input = AiRealtimeMrcpEventService.class.getDeclaredMethod("processWorkflowInput", type, String.class, long.class);
        input.setAccessible(true);
        input.invoke(service, runtime, "yes", 0L);
        assertFalse(field(type, runtime, "workflowSpeak", AtomicBoolean.class).get());
        assertNull(field(type, runtime, "activeSpeak", AtomicReference.class).get());
        input.invoke(service, runtime, "yes", 0L);
        verify(workflow, times(1)).voiceInput(anyString(), anyString(), anyString());
    }

    @Test
    void hangupDuringTurnInsertCancelsPersistedTurnWithoutAttachingIt() throws Exception {
        Class<?> type = Class.forName(AiRealtimeMrcpEventService.class.getName() + "$RuntimeSession");
        Object runtime = newRuntime(type);
        var mapper = mock(AiRealtimeCallTurnMapper.class);
        when(mapper.insert(any(AiRealtimeCallTurn.class))).thenAnswer(invocation -> {
            field(type, runtime, "closed", AtomicBoolean.class).set(true);
            return 1;
        });
        var service = mock(AiRealtimeMrcpEventService.class, CALLS_REAL_METHODS);
        setServiceField(service, "turnMapper", mapper);
        var process = AiRealtimeMrcpEventService.class.getDeclaredMethod("processTurn", type, String.class, long.class, long.class);
        process.setAccessible(true);
        process.invoke(service, runtime, "yes", 0L, 0L);
        var captured = org.mockito.ArgumentCaptor.forClass(AiRealtimeCallTurn.class);
        verify(mapper).updateById(captured.capture());
        assertEquals("CANCELLED", captured.getValue().getTurnState());
        assertNull(field(type, runtime, "currentTurn", AtomicReference.class).get());
        process.invoke(service, runtime, "yes", 0L, 0L);
        verify(mapper, times(1)).insert(any(AiRealtimeCallTurn.class));
    }

    @Test
    void completionFromInvalidatedGenerationCannotContinueEvenWithSameText() {
        assertTrue(AiRealtimeMrcpEventService.canContinuePlayback(false, 7L, 7L));
        assertFalse(AiRealtimeMrcpEventService.canContinuePlayback(false, 7L, 8L));
        assertFalse(AiRealtimeMrcpEventService.canContinuePlayback(false, 8L, 7L));
        assertFalse(AiRealtimeMrcpEventService.canContinuePlayback(true, 7L, 7L));
    }

    @Test
    void textFallbackRejectsOldLongSentenceContainingCurrentShortReply() {
        assertFalse(AiRealtimeMrcpEventService.matchesSpeakCompletion("好的", "unimrcp|voice|好的，请提供住院信息"));
        assertFalse(AiRealtimeMrcpEventService.matchesSpeakCompletion("好的", "unimrcp|voice|不好的"));
        assertTrue(AiRealtimeMrcpEventService.matchesSpeakCompletion("好的", "unimrcp|voice|好的"));
        assertTrue(AiRealtimeMrcpEventService.matchesSpeakCompletion("好的", "好的"));
    }
}
