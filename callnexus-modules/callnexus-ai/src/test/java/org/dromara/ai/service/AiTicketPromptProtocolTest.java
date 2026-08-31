package org.dromara.ai.service;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class AiTicketPromptProtocolTest {
    private final AiTicketPromptProtocol protocol = new AiTicketPromptProtocol();

    @Test
    void compileShouldReplaceRuntimeVariablesAndAppendReadOnlyProtocol() {
        String result = protocol.compile("对话={{conversation}}\n模板={{ticketTemplateSchema}}",
            Map.of("conversation", "客户：商品破损", "ticketTemplateSchema", "problem:TEXTAREA"));

        assertThat(result)
            .contains("客户：商品破损")
            .contains("problem:TEXTAREA")
            .contains("系统固定输出协议")
            .contains("additionalProperties")
            .contains("不得编造");
    }

    @Test
    void compileShouldRejectUnknownOrMissingRequiredVariables() {
        assertThatThrownBy(() -> protocol.compile("{{conversation}} {{unsafe}}", Map.of()))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("未知变量：{{unsafe}}")
            .hasMessageContaining("缺少必需变量：{{ticketTemplateSchema}}");
    }
}
