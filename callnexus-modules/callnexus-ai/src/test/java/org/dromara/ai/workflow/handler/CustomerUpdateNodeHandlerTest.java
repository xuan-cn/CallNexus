package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.domain.response.AiWorkflowCustomerContext;
import org.dromara.ai.domain.response.AiWorkflowCustomerField;
import org.dromara.ai.mapper.AiWorkflowContextMapper;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@Tag("dev")
class CustomerUpdateNodeHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldUpdateOnlyWhitelistedCustomerFields() throws Exception {
        AiWorkflowContextMapper mapper = mapperWithCustomer();
        CustomerUpdateNodeHandler handler = new CustomerUpdateNodeHandler(mapper);
        Map<String, Object> variables = Map.of(
            "customer.id", 10L,
            "customer.name", "李四",
            "customer.custom.company", "新公司",
            "customer.custom.unselected", "不能写入"
        );

        var result = TenantHelper.dynamic("000000", () -> handler.execute(new AiWorkflowNodeContext(read("""
            {"type":"CUSTOMER_UPDATE","config":{"fields":[
              {"key":"customer.name"},{"key":"customer.custom.company"}
            ]}}
            """), variables, null, 8L)));

        assertThat(result.variableUpdates()).containsEntry("customer.updated", true)
            .containsEntry("customer.updatedFieldCount", 2);
        verify(mapper).updateCustomerName(org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq("李四"));
        verify(mapper).updateCustomerFormData(org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.eq(10L), contains("\"company\":\"新公司\""));
    }

    @Test
    void shouldRejectFileFieldEvenWhenConfigured() throws Exception {
        AiWorkflowContextMapper mapper = mapperWithCustomer();
        CustomerUpdateNodeHandler handler = new CustomerUpdateNodeHandler(mapper);
        Map<String, Object> variables = Map.of("customer.id", 10L, "customer.custom.attachment", "123");

        assertThatThrownBy(() -> TenantHelper.dynamic("000000", () -> handler.execute(new AiWorkflowNodeContext(read("""
            {"type":"CUSTOMER_UPDATE","config":{"fields":[{"key":"customer.custom.attachment"}]}}
            """), variables, null, 8L))))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("不允许自动更新");
    }

    @Test
    void shouldKeepDesignerTestModeSideEffectFree() throws Exception {
        AiWorkflowContextMapper mapper = mock(AiWorkflowContextMapper.class);
        CustomerUpdateNodeHandler handler = new CustomerUpdateNodeHandler(mapper);
        Map<String, Object> variables = Map.of("workflow.testMode", true, "customer.id", 10L, "customer.name", "李四");

        var result = handler.execute(new AiWorkflowNodeContext(read("""
            {"type":"CUSTOMER_UPDATE","config":{"fields":[{"key":"customer.name"}]}}
            """), variables, null, 8L));

        assertThat(result.output()).contains("模拟更新");
        assertThat(result.variableUpdates()).containsEntry("customer.updated", true);
        verify(mapper, never()).updateCustomerName(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private AiWorkflowContextMapper mapperWithCustomer() {
        AiWorkflowContextMapper mapper = mock(AiWorkflowContextMapper.class);
        AiWorkflowCustomerContext customer = new AiWorkflowCustomerContext();
        customer.setCustomerId(10L);
        customer.setTemplateId(20L);
        customer.setFormData("{\"company\":\"旧公司\"}");
        when(mapper.findCustomerById(org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.eq(10L))).thenReturn(customer);
        when(mapper.findCustomerFields(org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.eq(20L))).thenReturn(List.of(
            new AiWorkflowCustomerField("company", "企业名称", "INPUT"),
            new AiWorkflowCustomerField("attachment", "附件", "FILE")
        ));
        when(mapper.updateCustomerFormData(org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        return mapper;
    }

    private com.fasterxml.jackson.databind.JsonNode read(String json) {
        try { return OBJECT_MAPPER.readTree(json); } catch (Exception exception) { throw new RuntimeException(exception); }
    }
}
