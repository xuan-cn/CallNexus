package org.dromara.ai.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.common.core.exception.ServiceException;
import java.util.Set;

public final class AiWorkflowAnswerPolicy {
    private AiWorkflowAnswerPolicy() { }
    public static String mode(JsonNode config, boolean disclosure) {
        String fallback = disclosure || config.path("knowledgeQuestionsEnabled").asBoolean(false) ? "KNOWLEDGE_ONLY" : "OFF";
        String value = config.path("answerMode").asText(fallback);
        if (!Set.of("OFF", "KNOWLEDGE_ONLY", "MODEL_FALLBACK").contains(value)) throw new ServiceException("插问回答方式无效");
        return value;
    }
    public static boolean accepts(String mode, AiChatTurnResult reply) {
        if ("OFF".equals(mode) || reply == null || reply.sourceType() == null || reply.answer() == null || reply.answer().isBlank()) return false;
        if ("MODEL_FALLBACK".equals(mode) && "MODEL".equals(reply.sourceType())) return true;
        return Set.of("FAQ_EXACT", "FAQ_SEMANTIC", "DOCUMENT", "DOCUMENT_DIRECT").contains(reply.sourceType())
            && reply.retrieval() != null && Boolean.TRUE.equals(reply.retrieval().get("hit")) && !Boolean.TRUE.equals(reply.retrieval().get("fallback"));
    }
}
