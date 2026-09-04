package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class IntentRouteNodeHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldIncrementConsecutiveClarificationCountOnFallback() throws Exception {
        AiIntentApplicationService intentService = mock(AiIntentApplicationService.class);
        AiIntentRecognitionResponse recognition = new AiIntentRecognitionResponse();
        recognition.setMatched(false);
        when(intentService.recognize(any())).thenReturn(recognition);
        IntentRouteNodeHandler handler = new IntentRouteNodeHandler(intentService);
        var node = OBJECT_MAPPER.readTree("{\"type\":\"INTENT_ROUTE\",\"config\":{\"intentCodes\":[\"CONFIRM\"]}}");

        var first = handler.execute(new AiWorkflowNodeContext(node,
            Map.of("workflow.clarifyCount", 0), "不清楚", 8L));
        var second = handler.execute(new AiWorkflowNodeContext(node,
            Map.of("workflow.clarifyCount", first.variableUpdates().get("workflow.clarifyCount")), "再说一次", 8L));

        assertThat(first.branchValue()).isEqualTo("FALLBACK");
        assertThat(first.variableUpdates()).containsEntry("workflow.clarifyCount", 1);
        assertThat(second.variableUpdates()).containsEntry("workflow.clarifyCount", 2);
    }

    @Test
    void shouldResetClarificationCountAfterIntentMatch() throws Exception {
        AiIntentApplicationService intentService = mock(AiIntentApplicationService.class);
        AiIntentRecognitionResponse recognition = new AiIntentRecognitionResponse();
        recognition.setMatched(true);
        recognition.setIntentCode("CONFIRM");
        recognition.setIntentName("确认");
        recognition.setConfidence(BigDecimal.valueOf(0.95D));
        when(intentService.recognize(any())).thenReturn(recognition);
        IntentRouteNodeHandler handler = new IntentRouteNodeHandler(intentService);

        var result = handler.execute(new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("{\"type\":\"INTENT_ROUTE\",\"config\":{\"intentCodes\":[\"CONFIRM\"]}}"),
            Map.of("workflow.clarifyCount", 2), "是的", 8L));

        assertThat(result.branchValue()).isEqualTo("CONFIRM");
        assertThat(result.variableUpdates()).containsEntry("workflow.clarifyCount", 0)
            .containsEntry("conversation.intentCode", "CONFIRM");
    }
}
