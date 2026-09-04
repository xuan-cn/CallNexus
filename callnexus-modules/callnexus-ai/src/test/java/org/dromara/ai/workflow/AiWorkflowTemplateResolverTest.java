package org.dromara.ai.workflow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class AiWorkflowTemplateResolverTest {
    private final AiWorkflowTemplateResolver resolver = new AiWorkflowTemplateResolver();

    @Test
    void shouldGenerateCustomerSalutationFromNameAndGender() {
        Map<String, Object> variables = Map.of("customer.name", "张三", "customer.gender", "MALE");

        assertThat(resolver.resolve("您好{{customer.salutation}}", variables)).isEqualTo("您好张三先生");
        assertThat(resolver.resolve("性别：{{customer.gender}}", variables)).isEqualTo("性别：男");
    }
}
