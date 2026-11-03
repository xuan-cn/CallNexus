package org.dromara.ai.workflow;

import org.dromara.ai.service.AiIntentApplicationService;
import static org.mockito.Mockito.mock;

final class WorkflowRuleTestSupport {
    private WorkflowRuleTestSupport() { }
    static AiConversationRuleMatcher matcher() {
        return matcher(mock(AiDialogueUnderstandingService.class));
    }
    static AiConversationRuleMatcher matcher(AiDialogueUnderstandingService ai) { return new AiConversationRuleMatcher(new DialogueAnswerMatcher(mock(AiIntentApplicationService.class)), ai, new AiWorkflowTemplateResolver()); }
}
