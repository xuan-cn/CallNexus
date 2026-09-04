package org.dromara.ai.workflow.handler;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.response.AiChatTurnResult;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AiReplyNodeHandler implements AiWorkflowNodeHandler {
    private final AiAgentApplicationService agentService;
    private final AiWorkflowTemplateResolver templateResolver;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("KNOWLEDGE_QUERY", "MODEL_REPLY");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String nodeType = context.node().path("type").asText();
        String template = "KNOWLEDGE_QUERY".equals(nodeType)
            ? context.node().path("config").path("queryTemplate").asText("{{conversation.currentInput}}")
            : context.node().path("config").path("promptTemplate").asText();
        String currentInput = StringUtils.blankToDefault(context.currentInput(),
            String.valueOf(context.variables().getOrDefault("conversation.currentInput", "")));
        String prompt = templateResolver.resolve(template, context.variables()).trim();
        if ("MODEL_REPLY".equals(nodeType) && prompt.isBlank()) {
            prompt = currentInput;
        }
        if (prompt.isBlank()) {
            throw new ServiceException("AI 回答节点没有可提交的查询内容");
        }

        Long conversationId = longValue(context.variables().get("ai.conversationId"));
        AiChatTurnResult turn = "MODEL_REPLY".equals(nodeType)
            ? agentService.chatOnceModel(context.aiAgentId(), conversationId, prompt)
            : agentService.chatOnce(context.aiAgentId(), conversationId, prompt);
        Map<String, Object> updates = new LinkedHashMap<>();
        if (turn.conversationId() != null) {
            updates.put("ai.conversationId", turn.conversationId());
        }
        updates.put("ai.answerSource", turn.sourceType());
        updates.put("ai.lastAnswer", turn.answer());
        if ("KNOWLEDGE_QUERY".equals(nodeType)) {
            Map<String, Object> retrieval = turn.retrieval() == null ? Map.of() : turn.retrieval();
            updates.put("knowledge.source", turn.sourceType());
            updates.put("knowledge.answer", turn.answer());
            copy(retrieval, updates, "hit", "knowledge.hit");
            copy(retrieval, updates, "score", "knowledge.score");
            copy(retrieval, updates, "threshold", "knowledge.threshold");
            copy(retrieval, updates, "hitCount", "knowledge.hitCount");
            copy(retrieval, updates, "fallback", "knowledge.fallback");
            copy(retrieval, updates, "reason", "knowledge.reason");
            copy(retrieval, updates, "bestFaqScore", "knowledge.bestFaqScore");
            copy(retrieval, updates, "faqThreshold", "knowledge.faqThreshold");
            copy(retrieval, updates, "bestDocumentScore", "knowledge.bestDocumentScore");
            copy(retrieval, updates, "documentThreshold", "knowledge.documentThreshold");
        }
        return new AiWorkflowNodeResult("SPEAK", null, turn.answer(), "TTS", updates);
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        if (source.containsKey(sourceKey) && source.get(sourceKey) != null) {
            target.put(targetKey, source.get(sourceKey));
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
