package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.response.AiWorkflowCustomerContext;
import org.dromara.ai.domain.response.AiWorkflowCustomerField;
import org.dromara.ai.mapper.AiWorkflowContextMapper;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CustomerUpdateNodeHandler implements AiWorkflowNodeHandler {
    private static final Set<String> BLOCKED_FIELD_TYPES = Set.of("FILE");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AiWorkflowContextMapper mapper;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("CUSTOMER_UPDATE");
    }

    @Override
    @SuppressWarnings("unchecked")
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        Long customerId = longValue(context.variables().get("customer.id"));
        if (customerId == null) throw new ServiceException("更新客户节点未找到客户，请先执行查询客户节点");
        List<String> fields = fields(context.node().path("config").path("fields"));
        if (fields.isEmpty()) throw new ServiceException("更新客户节点未选择允许更新的字段");
        if (Boolean.TRUE.equals(context.variables().get("workflow.testMode"))) {
            long available = fields.stream().filter(context.variables()::containsKey).count();
            return new AiWorkflowNodeResult("CONTINUE", null, "测试模式：已模拟更新客户资料", null,
                Map.of("customer.updated", available > 0, "customer.updatedFieldCount", available));
        }
        String tenantId = TenantHelper.getTenantId();
        AiWorkflowCustomerContext customer = mapper.findCustomerById(tenantId, customerId);
        if (customer == null) throw new ServiceException("需要更新的客户不存在");

        int updated = 0;
        if (fields.contains("customer.name") && context.variables().containsKey("customer.name")) {
            String name = String.valueOf(context.variables().get("customer.name")).trim();
            if (name.length() > 64) throw new ServiceException("客户姓名不能超过64个字符");
            mapper.updateCustomerName(tenantId, customerId, StringUtils.isBlank(name) ? null : name);
            updated++;
        }

        List<String> customKeys = fields.stream().filter(item -> item.startsWith("customer.custom."))
            .map(item -> item.substring("customer.custom.".length())).distinct().toList();
        if (!customKeys.isEmpty()) {
            if (customer.getTemplateId() == null) throw new ServiceException("客户未绑定表单模板，不能更新自定义字段");
            Map<String, AiWorkflowCustomerField> allowed = mapper.findCustomerFields(tenantId, customer.getTemplateId()).stream()
                .filter(item -> !BLOCKED_FIELD_TYPES.contains(item.fieldType()))
                .collect(Collectors.toMap(AiWorkflowCustomerField::fieldCode, item -> item));
            List<String> invalid = customKeys.stream().filter(key -> !allowed.containsKey(key)).toList();
            if (!invalid.isEmpty()) throw new ServiceException("更新客户节点包含模板外或不允许自动更新的字段：" + String.join(",", invalid));
            Map<String, Object> formData = readFormData(customer.getFormData());
            if (formData == null) formData = new LinkedHashMap<>();
            for (String key : customKeys) {
                String variableKey = "customer.custom." + key;
                if (!context.variables().containsKey(variableKey)) continue;
                formData.put(key, context.variables().get(variableKey));
                updated++;
            }
            if (mapper.updateCustomerFormData(tenantId, customerId, writeFormData(formData)) == 0) {
                throw new ServiceException("客户表单提交不存在，不能自动更新自定义字段");
            }
        }
        Map<String, Object> updates = Map.of("customer.updated", updated > 0, "customer.updatedFieldCount", updated);
        return new AiWorkflowNodeResult("CONTINUE", null, updated == 0 ? "没有可更新的客户字段" : "已更新客户资料", null, updates);
    }

    private List<String> fields(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            String value = item.isTextual() ? item.asText("") : item.path("key").asText("");
            if (value.equals("customer.name") || value.startsWith("customer.custom.")) result.add(value);
        });
        return result.stream().distinct().toList();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFormData(String json) {
        if (StringUtils.isBlank(json)) return new LinkedHashMap<>();
        try { return OBJECT_MAPPER.readValue(json, LinkedHashMap.class); }
        catch (Exception exception) { throw new ServiceException("客户表单数据无法解析：" + exception.getMessage()); }
    }

    private String writeFormData(Map<String, Object> values) {
        try { return OBJECT_MAPPER.writeValueAsString(values); }
        catch (Exception exception) { throw new ServiceException("客户表单数据无法保存：" + exception.getMessage()); }
    }
}
