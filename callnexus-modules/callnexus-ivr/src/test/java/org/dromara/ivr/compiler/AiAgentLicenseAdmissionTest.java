package org.dromara.ivr.compiler;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.dromara.ai.service.AiRealtimeDialplanService;
import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.graph.IvrNodeDefinition;
import org.dromara.ivr.support.IvrDialplanRenderSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
@Tag("prod")
class AiAgentLicenseAdmissionTest {
    @Test
    void blockedAiNodeDoesNotPrepareVoiceOrChangeOtherDialplanNodes() {
        var service = mock(AiRealtimeDialplanService.class);
        var compiler = new AiAgentNodeCompiler(service);
        var flow = new IvrFlow();
        flow.setId(1L);
        var node = new IvrNodeDefinition("ai", "AI_AGENT", "AI", JsonNodeFactory.instance.objectNode().put("aiAgentId", 21));
        var xml = new StringBuilder("<!-- Other IVR nodes remain intact. -->\n");
        when(service.allowVoiceAdmission(21L)).thenReturn(false);

        compiler.compile(new IvrNodeContext("000000", flow, 2L, null, null, null, node, xml, new IvrDialplanRenderSupport(), null));

        assertTrue(xml.toString().startsWith("<!-- Other IVR nodes remain intact. -->"));
        assertTrue(xml.toString().contains("callnexus_ivr_1_ai"));
        assertTrue(xml.toString().contains("CALL_REJECTED"));
        assertFalse(xml.toString().contains("callnexus_ai_active"));
        assertFalse(xml.toString().contains("park"));
        verify(service).allowVoiceAdmission(21L);
        verifyNoMoreInteractions(service);
    }
}
