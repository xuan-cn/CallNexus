package org.dromara.ai.service;

import org.dromara.ai.provider.StreamingTtsSession;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiRealtimeTtsInternalServiceTest {

    @Test
    void shouldReleaseProviderSessionAndTimerWhenCancelled() {
        StreamingTtsSession session = mock(StreamingTtsSession.class);
        ScheduledFuture<?> timer = mock(ScheduledFuture.class);
        AiRealtimeTtsInternalService.StreamingExecution execution =
            new AiRealtimeTtsInternalService.StreamingExecution();
        execution.attachSession(session);
        execution.attachTimer(timer);

        execution.cancel();

        assertTrue(execution.isFinished());
        verify(timer).cancel(false);
        verify(session).close();
    }

    @Test
    void shouldImmediatelyReleaseResourcesAttachedAfterCancellation() {
        StreamingTtsSession session = mock(StreamingTtsSession.class);
        ScheduledFuture<?> timer = mock(ScheduledFuture.class);
        AiRealtimeTtsInternalService.StreamingExecution execution =
            new AiRealtimeTtsInternalService.StreamingExecution();

        execution.cancel();
        execution.attachSession(session);
        execution.attachTimer(timer);

        verify(timer).cancel(false);
        verify(session).close();
    }
}
