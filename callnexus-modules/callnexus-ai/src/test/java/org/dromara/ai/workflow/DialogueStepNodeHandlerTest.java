package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.workflow.handler.DialogueStepNodeHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class DialogueStepNodeHandlerTest {
    private final DialogueStepNodeHandler handler = new DialogueStepNodeHandler(new AiWorkflowTemplateResolver(),
        org.mockito.Mockito.mock(AiDialogueUnderstandingService.class), org.mockito.Mockito.mock(org.dromara.ai.service.AiAgentApplicationService.class), org.mockito.Mockito.mock(AiWorkflowSlotExtractionService.class), new DialogueAnswerMatcher(org.mockito.Mockito.mock(org.dromara.ai.service.AiIntentApplicationService.class)));
    private final Map<String, Object> variables = new HashMap<>();

    private AiWorkflowNodeResult answer(String input) throws Exception {
        var node = new ObjectMapper().readTree("""
            {"id":"q","type":"DIALOGUE_STEP","config":{"text":"您好{{customer.name}}，需要办理吗？",
            "acceptedAnswers":"是的\\n需要","declinedAnswers":"不需要\\n不用","maxRetries":2,"retryText":"请问需要办理吗？"}}
            """);
        var result = handler.execute(new AiWorkflowNodeContext(node, variables, input, null));
        variables.putAll(result.variableUpdates());
        return result;
    }

    @Test void enteringDoesNotReusePreviousAnswer() throws Exception {
        variables.put("customer.name", "张三");
        var result = answer("是的");
        assertEquals("SPEAK_RETRY", result.status());
        assertTrue(result.output().contains("张三"));
        assertNull(result.branchValue());
    }
    @Test void explicitConfirmationCompletesAndReentryAsksAgain() throws Exception {
        answer("");
        assertEquals("COMPLETE", answer(" 是 的！ ").branchValue());
        assertEquals("SPEAK_RETRY", answer("是的").status());
    }
    @Test void rejectionMustNotMatchPositiveSubstring() throws Exception {
        answer("");
        assertEquals("DECLINED", answer("不需要。").branchValue());
    }
    @Test void clarificationLimitExcludesInitialQuestion() throws Exception {
        answer("");
        assertEquals("SPEAK_RETRY", answer("不知道").status());
        assertEquals("SPEAK_RETRY", answer("什么业务").status());
        assertEquals("LIMIT", answer("没听清").branchValue());
    }
    @Test void longOrUnclearAnswerCannotAutomaticallyPass() throws Exception {
        answer("");
        assertEquals("SPEAK_RETRY", answer("是的但是我还没决定是否办理").status());
        assertEquals("SPEAK_RETRY", answer("").status());
    }
    @Test void emptyAnswerDefinitionsDoNotCreateAnImplicitConfirmation() {
        assertTrue(DialogueStepNodeHandler.answers(null).isEmpty());
        assertTrue(DialogueStepNodeHandler.answers("  \n。\n").isEmpty());
    }
}
