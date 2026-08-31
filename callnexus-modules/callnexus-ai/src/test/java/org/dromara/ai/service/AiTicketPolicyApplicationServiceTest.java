package org.dromara.ai.service;

import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.request.AiTicketPromptRequest;
import org.dromara.ai.domain.response.AiTicketPromptValidationResponse;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiTicketPolicyMapper;
import org.dromara.ai.mapper.AiTicketPromptVersionMapper;
import org.dromara.ai.service.impl.AiTicketPolicyApplicationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiTicketPolicyApplicationServiceTest {

    private AiTicketPolicyApplicationService service;

    @BeforeEach
    void setUp() {
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        when(agentMapper.selectById(1L)).thenReturn(new AiAgent());
        service = new AiTicketPolicyApplicationServiceImpl(agentMapper,
            mock(AiTicketPolicyMapper.class), mock(AiTicketPromptVersionMapper.class));
    }

    @Test
    void validPromptShouldCompileWithReadOnlyProtocol() {
        AiTicketPromptRequest request = request("对话：{{conversation}}\n模板：{{ticketTemplateSchema}}\n客户：{{customerProfile}}");

        AiTicketPromptValidationResponse response = service.validatePrompt(1L, request);

        assertThat(response.isValid()).isTrue();
        assertThat(response.getErrors()).isEmpty();
        assertThat(response.getCompiledPreview())
            .contains("订单号 CNX20260828001")
            .contains("系统固定输出协议")
            .contains("additionalProperties")
            .contains("系统固定安全约束");
    }

    @Test
    void missingRequiredAndUnknownVariablesShouldBeRejected() {
        AiTicketPromptValidationResponse response = service.validatePrompt(1L, request("{{conversation}} {{unsafeValue}}"));

        assertThat(response.isValid()).isFalse();
        assertThat(response.getErrors())
            .contains("未知变量：{{unsafeValue}}", "缺少必需变量：{{ticketTemplateSchema}}");
        assertThat(response.getCompiledPreview()).isNull();
    }

    private AiTicketPromptRequest request(String content) {
        AiTicketPromptRequest request = new AiTicketPromptRequest();
        request.setPromptContent(content);
        return request;
    }
}
