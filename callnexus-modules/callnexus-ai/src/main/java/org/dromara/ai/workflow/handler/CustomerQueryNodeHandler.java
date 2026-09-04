package org.dromara.ai.workflow.handler;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.response.AiWorkflowCustomerContext;
import org.dromara.ai.mapper.AiWorkflowContextMapper;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CustomerQueryNodeHandler implements AiWorkflowNodeHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AiWorkflowContextMapper mapper;
    private final AiWorkflowTemplateResolver resolver;

    @Override
    public Set<String> nodeTypes() {
        return Set.of("CUSTOMER_QUERY");
    }

    @Override
    @SuppressWarnings("unchecked")
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String template = context.node().path("config").path("phoneTemplate").asText("{{customer.phone}}");
        String phone = resolver.resolve(template, context.variables()).replaceAll("[\\s-]", "");
        if (StringUtils.isBlank(phone)) throw new ServiceException("查询客户节点没有可查询的电话号码");
        AiWorkflowCustomerContext customer = mapper.findCustomerByPhone(TenantHelper.getTenantId(), phone);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("customer.found", customer != null);
        if (customer != null) {
            updates.put("customer.id", customer.getCustomerId());
            updates.put("customer.phone", customer.getPhone());
            if (StringUtils.isNotBlank(customer.getCustomerName())) updates.put("customer.name", customer.getCustomerName());
            if (customer.getTemplateId() != null) updates.put("customer.templateId", customer.getTemplateId());
            if (StringUtils.isNotBlank(customer.getFormData())) {
                Map<String, Object> formData;
                try {
                    formData = OBJECT_MAPPER.readValue(customer.getFormData(), LinkedHashMap.class);
                } catch (Exception exception) {
                    throw new ServiceException("客户表单数据无法解析：" + exception.getMessage());
                }
                if (formData != null) formData.forEach((key, value) -> updates.put("customer.custom." + key, value));
            }
        }
        return new AiWorkflowNodeResult("CONTINUE", null, customer == null ? "未查询到客户" : "已查询到客户", null, updates);
    }
}
