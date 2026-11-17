package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.service.AiWorkflowTicketService;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TicketCreateNodeHandler implements AiWorkflowNodeHandler {
    private final ObjectProvider<AiWorkflowTicketService> ticketServices;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("TICKET_CREATE", "TICKET_DRAFT_PREPARE");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        JsonNode config = context.node().path("config");
        if ("TICKET_DRAFT_PREPARE".equals(context.node().path("type").asText())) {
            return new AiWorkflowNodeResult("CONTINUE", null, "已准备缺项草稿，未创建工单", null, Map.of());
        }
        long templateId = config.path("templateId").asLong(0);
        JsonNode mappings = config.path("fieldMappings");
        if (templateId <= 0 || !mappings.isObject()) return result(new AiWorkflowTicketService.Result(null, "未配置工单模板或字段映射"), false);
        AiWorkflowTicketService service = ticketServices.getIfAvailable();
        if (service == null) return result(new AiWorkflowTicketService.Result(null, "工单服务不可用"), false);
        Map<String, Object> values = new LinkedHashMap<>();
        mappings.fields().forEachRemaining(mapping ->
            values.put(mapping.getKey(), context.variables().get(mapping.getValue().asText())));
        boolean test = "TEST".equals(context.channelType());
        var registration = service.register(templateId, values, context.businessCallId(), context.aiAgentId(), test);
        return result(registration, test);
    }

    private AiWorkflowNodeResult result(AiWorkflowTicketService.Result registration, boolean test) {
        Long ticketId = registration.ticketId();
        String error = registration.validationError();
        boolean created = error == null && (test || ticketId != null);
        return new AiWorkflowNodeResult("CONTINUE", null,
            created ? (test ? "测试模式：工单校验通过，未写入" : "已登记工单") : error, null,
            Map.of("ticket.created", created, "ticket.id", ticketId == null ? "" : ticketId.toString(),
                "ticket.error", error == null ? "" : error), test ? registration.preview() : null);
    }
}
