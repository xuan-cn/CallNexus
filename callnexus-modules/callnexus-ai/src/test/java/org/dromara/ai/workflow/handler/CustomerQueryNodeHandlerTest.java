package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.response.AiWorkflowCustomerContext;
import org.dromara.ai.mapper.AiWorkflowContextMapper;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowTemplateResolver;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class CustomerQueryNodeHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldLoadCustomerAndDynamicFormVariables() throws Exception {
        AiWorkflowContextMapper mapper = mock(AiWorkflowContextMapper.class);
        AiWorkflowTemplateResolver resolver = mock(AiWorkflowTemplateResolver.class);
        Map<String, Object> variables = Map.of("customer.phone", "138 0000-0000");
        when(resolver.resolve("{{customer.phone}}", variables)).thenReturn("138 0000-0000");
        AiWorkflowCustomerContext customer = new AiWorkflowCustomerContext();
        customer.setCustomerId(10L);
        customer.setCustomerName("张三");
        customer.setPhone("13800000000");
        customer.setTemplateId(20L);
        customer.setFormData("{\"company\":\"示例公司\"}");
        when(mapper.findCustomerByPhone(org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.eq("13800000000"))).thenReturn(customer);
        CustomerQueryNodeHandler handler = new CustomerQueryNodeHandler(mapper, resolver);

        var result = TenantHelper.dynamic("000000", () -> handler.execute(new AiWorkflowNodeContext(
            read("{\"type\":\"CUSTOMER_QUERY\",\"config\":{\"phoneTemplate\":\"{{customer.phone}}\"}}"),
            variables, null, 8L)));

        assertThat(result.variableUpdates()).containsEntry("customer.found", true)
            .containsEntry("customer.id", 10L)
            .containsEntry("customer.name", "张三")
            .containsEntry("customer.custom.company", "示例公司");
    }

    private com.fasterxml.jackson.databind.JsonNode read(String json) {
        try { return OBJECT_MAPPER.readTree(json); } catch (Exception exception) { throw new RuntimeException(exception); }
    }
}
