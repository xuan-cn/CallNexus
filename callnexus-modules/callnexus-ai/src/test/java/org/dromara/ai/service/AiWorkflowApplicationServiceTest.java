package org.dromara.ai.service;

import org.dromara.ai.domain.AiWorkflow;
import org.dromara.ai.domain.AiWorkflowVersion;
import org.dromara.ai.domain.response.AiWorkflowValidationResponse;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiAgentWorkflowBindingMapper;
import org.dromara.ai.mapper.AiWorkflowMapper;
import org.dromara.ai.mapper.AiWorkflowVersionMapper;
import org.dromara.ai.service.impl.AiWorkflowApplicationServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiWorkflowApplicationServiceTest {

    private final AiWorkflowMapper workflowMapper = mock(AiWorkflowMapper.class);
    private final AiWorkflowVersionMapper versionMapper = mock(AiWorkflowVersionMapper.class);
    private final AiAgentWorkflowBindingMapper bindingMapper = mock(AiAgentWorkflowBindingMapper.class);
    private final AiAgentMapper agentMapper = mock(AiAgentMapper.class);
    private final AiWorkflowApplicationService service = new AiWorkflowApplicationServiceImpl(
        workflowMapper, versionMapper, bindingMapper, agentMapper);

    @Test
    void validatesReachableFiniteWorkflow() {
        mockDraft("""
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"welcome","type":"TEMPLATE_REPLY","config":{"text":"您好"}},
              {"id":"end","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"welcome"},
              {"source":"welcome","target":"end"}
            ]}
            """);

        AiWorkflowValidationResponse result = service.validateDraft(1L);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void rejectsMissingBranchConditionsAndCycles() {
        mockDraft("""
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"intent","type":"INTENT_ROUTE","config":{"intentCodes":["SALES_INTERESTED"]}},
              {"id":"reply","type":"TEMPLATE_REPLY","config":{}}
            ],"edges":[
              {"source":"start","target":"intent"},
              {"source":"intent","target":"reply","condition":""},
              {"source":"reply","target":"intent"}
            ]}
            """);

        AiWorkflowValidationResponse result = service.validateDraft(1L);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(item -> item.contains("分支条件"));
        assertThat(result.getErrors()).anyMatch(item -> item.contains("循环连线"));
    }

    @Test
    void rejectsSelectedIntentWithoutMatchingEdgeAndFallback() {
        mockDraft("""
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"intent","type":"INTENT_ROUTE","config":{"intentCodes":["CONFIRM_AGREE","REJECT_GENERAL"]}},
              {"id":"confirm","type":"END","config":{}},
              {"id":"reject","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"intent"},
              {"source":"intent","target":"confirm","condition":"CONFIRM_AGREE"},
              {"source":"intent","target":"reject","condition":"REJECT_GENERAL"}
            ]}
            """);

        AiWorkflowValidationResponse result = service.validateDraft(1L);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(item -> item.contains("未命中任何意图"));
    }

    @Test
    void rejectsSelectedIntentWithoutMatchingEdge() {
        mockDraft("""
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"intent","type":"INTENT_ROUTE","config":{"intentCodes":["CONFIRM_AGREE","REJECT_GENERAL"]}},
              {"id":"confirm","type":"END","config":{}},
              {"id":"fallback","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"intent"},
              {"source":"intent","target":"confirm","condition":"CONFIRM_AGREE"},
              {"source":"intent","target":"fallback","condition":"FALLBACK"}
            ]}
            """);

        AiWorkflowValidationResponse result = service.validateDraft(1L);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(item -> item.contains("REJECT_GENERAL"));
    }

    private void mockDraft(String definitionJson) {
        AiWorkflow workflow = new AiWorkflow();
        workflow.setId(1L);
        AiWorkflowVersion draft = new AiWorkflowVersion();
        draft.setId(10L);
        draft.setWorkflowId(1L);
        draft.setStatus("DRAFT");
        draft.setDefinitionJson(definitionJson);
        when(workflowMapper.selectById(1L)).thenReturn(workflow);
        when(versionMapper.selectOne(any())).thenReturn(draft);
    }
}
