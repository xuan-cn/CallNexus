package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowSlotExtractionService;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SlotExtractNodeHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldOnlyExposeConfiguredExtractionResults() throws Exception {
        AiWorkflowSlotExtractionService service = mock(AiWorkflowSlotExtractionService.class);
        AiWorkflowTemplateResolver resolver = mock(AiWorkflowTemplateResolver.class);
        Map<String, Object> variables = Map.of("conversation.currentInput", "我叫李四，预算两万元");
        when(resolver.resolve("{{conversation.currentInput}}", variables)).thenReturn("我叫李四，预算两万元");
        when(service.extract(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.anyString(), anyList()))
            .thenReturn(Map.of("customer.name", "李四", "workflow.budget", 20000));
        SlotExtractNodeHandler handler = new SlotExtractNodeHandler(service, resolver);

        var result = handler.execute(new AiWorkflowNodeContext(OBJECT_MAPPER.readTree("""
            {"type":"SLOT_EXTRACT","config":{"sourceTemplate":"{{conversation.currentInput}}","fields":[
              {"key":"customer.name","label":"客户姓名","type":"STRING"},
              {"key":"workflow.budget","label":"预算","type":"NUMBER"},
              {"key":"system.forbidden","label":"非法字段","type":"STRING"}
            ]}}
            """), variables, "我叫李四，预算两万元", 8L));

        assertThat(result.variableUpdates()).containsEntry("customer.name", "李四")
            .containsEntry("workflow.budget", 20000)
            .containsEntry("slot.extracted", true)
            .containsEntry("slot.extractedCount", 2);
        verify(service).extract(org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.argThat(targets -> targets.size() == 2));
    }
}
