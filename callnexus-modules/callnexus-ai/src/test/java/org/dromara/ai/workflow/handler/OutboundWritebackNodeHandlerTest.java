package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowOutboundWritebackService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class OutboundWritebackNodeHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldKeepDesignerTestModeSideEffectFree() throws Exception {
        AiWorkflowOutboundWritebackService service = mock(AiWorkflowOutboundWritebackService.class);
        ObjectProvider<AiWorkflowOutboundWritebackService> provider = provider(service);
        OutboundWritebackNodeHandler handler = new OutboundWritebackNodeHandler(provider);

        var result = handler.execute(new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("{\"type\":\"AUTO_OUTBOUND_WRITEBACK\",\"config\":{\"resultCode\":\"INTERESTED\"}}"),
            Map.of("workflow.testMode", true), null, 1L));

        assertThat(result.variableUpdates()).containsEntry("workflow.outboundResult", "INTERESTED");
        verify(service, never()).writeBack(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldWriteBackRealOutboundContext() throws Exception {
        AiWorkflowOutboundWritebackService service = mock(AiWorkflowOutboundWritebackService.class);
        OutboundWritebackNodeHandler handler = new OutboundWritebackNodeHandler(provider(service));

        handler.execute(new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("{\"type\":\"AUTO_OUTBOUND_WRITEBACK\",\"config\":{\"resultCode\":\"CALLBACK_REQUESTED\"}}"),
            Map.of("workflow.sceneType", "VOICE_OUTBOUND", "outbound.taskId", 10L,
                "outbound.memberId", 20L, "call.businessCallId", "call-1"), null, 1L));

        verify(service).writeBack(10L, 20L, "call-1", "CALLBACK_REQUESTED");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<AiWorkflowOutboundWritebackService> provider(AiWorkflowOutboundWritebackService service) {
        ObjectProvider<AiWorkflowOutboundWritebackService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }
}
