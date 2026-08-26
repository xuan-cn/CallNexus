package org.dromara.ai.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class RealtimeAudioTokenServiceTest {

    @Test
    void shouldIssueAndVerifyAiRealtimeToken() {
        AiKnowledgeProperties properties = properties();
        AiRealtimeTokenService service = new AiRealtimeTokenService(
            properties, new RealtimeAudioTokenCodec(properties, new ObjectMapper()));

        AiRealtimeClaims claims = service.verify(service.issue("000000", 11L, 22L, 33L));

        assertEquals("000000", claims.tenantId());
        assertEquals(11L, claims.agentId());
        assertEquals(22L, claims.flowId());
        assertEquals(33L, claims.nodeId());
    }

    @Test
    void shouldIssueAndVerifyStreamingAsrToken() {
        AiKnowledgeProperties properties = properties();
        StreamingAsrTokenService service = new StreamingAsrTokenService(
            properties, new RealtimeAudioTokenCodec(properties, new ObjectMapper()));

        StreamingAsrClaims claims = service.verify(service.issue(
            "000000", 33L, "call-1", "leg-1", "CUSTOMER"));

        assertEquals("000000", claims.tenantId());
        assertEquals(33L, claims.nodeId());
        assertEquals("call-1", claims.businessCallId());
        assertEquals("leg-1", claims.legUuid());
        assertEquals("CUSTOMER", claims.speaker());
    }

    @Test
    void shouldRejectTamperedToken() {
        AiKnowledgeProperties properties = properties();
        StreamingAsrTokenService service = new StreamingAsrTokenService(
            properties, new RealtimeAudioTokenCodec(properties, new ObjectMapper()));
        String token = service.issue("000000", 33L, "call-1", "leg-1", "AGENT");

        assertThrows(ServiceException.class, () -> service.verify(token + "x"));
    }

    private AiKnowledgeProperties properties() {
        AiKnowledgeProperties properties = new AiKnowledgeProperties();
        properties.setRealtimeTokenSecret("0123456789abcdef0123456789abcdef");
        properties.setRealtimeTokenTtlSeconds(300);
        return properties;
    }
}
