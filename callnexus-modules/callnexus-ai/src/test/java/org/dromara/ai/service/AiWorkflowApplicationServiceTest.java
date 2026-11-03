package org.dromara.ai.service;

import org.dromara.ai.domain.AiWorkflow;
import org.dromara.ai.domain.AiWorkflowVersion;
import org.dromara.ai.domain.response.AiWorkflowValidationResponse;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiAgentWorkflowBindingMapper;
import org.dromara.ai.mapper.AiWorkflowMapper;
import org.dromara.ai.mapper.AiWorkflowVersionMapper;
import org.dromara.ai.service.impl.AiWorkflowApplicationServiceImpl;
import org.dromara.resource.media.mapper.MediaAssetMapper;
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
    private final MediaAssetMapper mediaAssetMapper = mock(MediaAssetMapper.class);
    private final AiWorkflowApplicationService service = new AiWorkflowApplicationServiceImpl(
        workflowMapper, versionMapper, bindingMapper, agentMapper, mediaAssetMapper);

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
    void dialogueFieldsMustReferenceDeclaredTextVariables() {
        String graph = """
            {"schemaVersion":"1.0","variables":[{"key":"workflow.custom_hospital","type":"%s"}],"nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"ask","type":"DIALOGUE_STEP","config":{"goal":"收集医院","text":"在哪家医院？","retryText":"请补充医院",
                "acceptedAnswers":"是的","declinedAnswers":"不是","aiUnderstandingEnabled":true,
                "collectedFields":[{"key":"workflow.custom_hospital","label":"医院","type":"STRING"}]}},
              {"id":"end","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"ask"},
              {"source":"ask","target":"end","condition":"COMPLETE"},
              {"source":"ask","target":"end","condition":"DECLINED"},
              {"source":"ask","target":"end","condition":"LIMIT"}
            ]}
            """;
        mockDraft(graph.formatted("NUMBER"));
        assertThat(service.validateDraft(1L).getErrors()).anyMatch(item -> item.contains("声明类型必须是文本"));
        mockDraft(graph.formatted("STRING"));
        assertThat(service.validateDraft(1L).getErrors()).isEmpty();
    }

    @Test
    void validatesIntentAnswerConfigurationWithoutExactExamples() {
        String graph = """
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"ask","type":"DIALOGUE_STEP","config":{"goal":"确认需求","text":"需要办理吗？","retryText":"请确认",
                "acceptedMatchMode":"INTENT","acceptedIntentCodes":["CONFIRM"]}},
              {"id":"end","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"ask"},
              {"source":"ask","target":"end","condition":"COMPLETE"},
              {"source":"ask","target":"end","condition":"DECLINED"},
              {"source":"ask","target":"end","condition":"LIMIT"}
            ]}
            """;
        mockDraft(graph);
        assertThat(service.validateDraft(1L).getErrors()).isEmpty();
        mockDraft(graph.replace("[\"CONFIRM\"]", "[]"));
        assertThat(service.validateDraft(1L).getErrors()).anyMatch(item -> item.contains("至少选择一个意图"));
        mockDraft(graph.replace("\"acceptedMatchMode\":\"INTENT\"", "\"acceptedMatchMode\":\"EXACT\""));
        assertThat(service.validateDraft(1L).getErrors()).anyMatch(item -> item.contains("明确通过"));
    }

    @Test
    void validatesBusinessOptionBranchesAndRejectsMissingOrUnknownExits() {
        String graph = """
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"ask","type":"DIALOGUE_STEP","config":{"goal":"确认身份","text":"本人还是家属？","retryText":"请确认身份",
                "answerOptions":[{"code":"SELF","label":"本人","answers":"本人"},{"code":"FAMILY","label":"家属","answers":"家属"}]}},
              {"id":"end","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"ask"},
              {"source":"ask","target":"end","condition":"OPTION_SELF"},
              {"id":"family-edge","source":"ask","target":"end","condition":"OPTION_FAMILY"},
              {"source":"ask","target":"end","condition":"DECLINED"},
              {"source":"ask","target":"end","condition":"LIMIT"}
            ]}
            """;
        mockDraft(graph);
        assertThat(service.validateDraft(1L).getErrors()).isEmpty();
        mockDraft(graph.replace("OPTION_FAMILY", "OPTION_OTHER"));
        assertThat(service.validateDraft(1L).getErrors()).anyMatch(item -> item.contains("全部业务选项"));
        assertThat(service.validateDraft(1L).getErrors()).anyMatch(item -> item.contains("不在已配置"));
        assertThat(service.validateDraft(1L).getIssues()).anySatisfy(issue -> {
            assertThat(issue.edgeId()).isEqualTo("family-edge");
            assertThat(issue.nodeId()).isEqualTo("ask");
            assertThat(issue.condition()).isEqualTo("OPTION_OTHER");
        });
        mockDraft(graph.replace("\"家属\"}", "\"本人\"}"));
        assertThat(service.validateDraft(1L).getErrors()).anyMatch(item -> item.contains("重复"));
        assertThat(service.validateDraft(1L).getErrors()).noneMatch(item -> item.contains("不在已配置"));
        assertThat(service.validateDraft(1L).getIssues()).anySatisfy(issue -> {
            assertThat(issue.nodeId()).isEqualTo("ask");
            assertThat(issue.field()).isEqualTo("answerOptions");
            assertThat(issue.edgeId()).isNull();
        });
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

    @Test
    void acceptsInternalRetryWithUnifiedContinueBranch() {
        mockDraft("""
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"intent","type":"INTENT_ROUTE","config":{"intentCodes":["CONFIRM_AGREE"],
                "fallbackMode":"FIXED_RETRY","fallbackText":"请再确认一次","maxRetries":2,"retryLimitAction":"CONTINUE"}},
              {"id":"confirm","type":"END","config":{}},
              {"id":"continue","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"intent"},
              {"source":"intent","target":"confirm","condition":"CONFIRM_AGREE"},
              {"source":"intent","target":"continue","condition":"CONTINUE"}
            ]}
            """);

        AiWorkflowValidationResponse result = service.validateDraft(1L);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void acceptsInheritedPolicyWithDirectLimitActionAndNoFallbackEdge() {
        mockDraft("""
            {"schemaVersion":"1.0","settings":{"intentFallback":{
              "fallbackMode":"FIXED_RETRY","fallbackText":"请再确认一次","maxRetries":2,
              "retryLimitAction":"TRANSFER_EXTENSION","limitExtension":"1001"
            }},"nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"intent","type":"INTENT_ROUTE","config":{"intentCodes":["CONFIRM_AGREE"],"fallbackMode":"INHERIT"}},
              {"id":"confirm","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"intent"},
              {"source":"intent","target":"confirm","condition":"CONFIRM_AGREE"}
            ]}
            """);

        AiWorkflowValidationResponse result = service.validateDraft(1L);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void acceptsCycleControlledByLoopLimit() {
        mockDraft("""
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"wait","type":"WAIT_INPUT","config":{"timeoutSeconds":15}},
              {"id":"intent","type":"INTENT_ROUTE","config":{"intentCodes":["CONFIRM_AGREE","REJECT_GENERAL"]}},
              {"id":"knowledge","type":"KNOWLEDGE_QUERY","config":{"queryTemplate":"{{conversation.currentInput}}"}},
              {"id":"limit","type":"LOOP_LIMIT","config":{"maxIterations":3}},
              {"id":"end","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"wait"},
              {"source":"wait","target":"intent"},
              {"source":"intent","target":"end","condition":"CONFIRM_AGREE"},
              {"source":"intent","target":"end","condition":"REJECT_GENERAL"},
              {"source":"intent","target":"knowledge","condition":"FALLBACK"},
              {"source":"knowledge","target":"limit"},
              {"source":"limit","target":"wait","condition":"CONTINUE"},
              {"source":"limit","target":"end","condition":"LIMIT"}
            ]}
            """);

        AiWorkflowValidationResponse result = service.validateDraft(1L);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void rejectsLoopLimitWhoseLimitBranchReturnsToCycle() {
        mockDraft("""
            {"schemaVersion":"1.0","nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"wait","type":"WAIT_INPUT","config":{"timeoutSeconds":15}},
              {"id":"limit","type":"LOOP_LIMIT","config":{"maxIterations":2}},
              {"id":"end","type":"END","config":{}}
            ],"edges":[
              {"source":"start","target":"wait"},
              {"source":"wait","target":"limit"},
              {"source":"limit","target":"wait","condition":"CONTINUE"},
              {"source":"limit","target":"wait","condition":"LIMIT"}
            ]}
            """);

        AiWorkflowValidationResponse result = service.validateDraft(1L);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(item -> item.contains("达到上限") && item.contains("离开循环"));
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
