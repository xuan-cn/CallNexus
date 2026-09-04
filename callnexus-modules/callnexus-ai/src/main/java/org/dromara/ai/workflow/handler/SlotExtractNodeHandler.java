package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.ai.workflow.AiWorkflowSlotExtractionService;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SlotExtractNodeHandler implements AiWorkflowNodeHandler {
    private final AiWorkflowSlotExtractionService extractionService;
    private final AiWorkflowTemplateResolver resolver;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("SLOT_EXTRACT");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        List<AiWorkflowSlotExtractionService.Target> targets = targets(context.node().path("config").path("fields"));
        if (targets.isEmpty()) throw new ServiceException("信息提取节点未选择提取字段");
        String sourceTemplate = context.node().path("config").path("sourceTemplate").asText("{{conversation.currentInput}}");
        String source = resolver.resolve(sourceTemplate, context.variables()).trim();
        Map<String, Object> extracted = extractionService.extract(context.aiAgentId(), source, targets);
        Map<String, Object> updates = new LinkedHashMap<>(extracted);
        updates.put("slot.extracted", !extracted.isEmpty());
        updates.put("slot.extractedCount", extracted.size());
        return new AiWorkflowNodeResult("CONTINUE", null,
            extracted.isEmpty() ? "未提取到信息" : "已提取 " + extracted.size() + " 个字段", null, updates);
    }

    private List<AiWorkflowSlotExtractionService.Target> targets(JsonNode fields) {
        if (!fields.isArray()) return List.of();
        List<AiWorkflowSlotExtractionService.Target> result = new ArrayList<>();
        fields.forEach(field -> {
            if (field.isTextual()) {
                String key = field.asText("").trim();
                if (!key.isEmpty()) result.add(new AiWorkflowSlotExtractionService.Target(key, key, "STRING"));
                return;
            }
            String key = field.path("key").asText("").trim();
            if (!key.isEmpty()) result.add(new AiWorkflowSlotExtractionService.Target(
                key, field.path("label").asText(key), field.path("type").asText("STRING")));
        });
        return result.stream().filter(item -> item.key().startsWith("workflow.")
            || item.key().equals("customer.name") || item.key().equals("customer.gender")
            || item.key().startsWith("customer.custom.")).distinct().toList();
    }
}
