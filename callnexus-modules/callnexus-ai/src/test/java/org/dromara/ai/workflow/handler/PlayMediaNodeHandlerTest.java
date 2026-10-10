package org.dromara.ai.workflow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class PlayMediaNodeHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final PlayMediaNodeHandler handler = new PlayMediaNodeHandler();

    @Test
    void returnsMediaPlaybackAction() throws Exception {
        AiWorkflowNodeResult result = handler.execute(new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("{\"type\":\"PLAY_MEDIA\",\"config\":{\"mediaId\":\"12345\"}}"),
            Map.of(), null, 1L, "VOICE"));

        assertThat(result.status()).isEqualTo("PLAY_MEDIA");
        assertThat(result.output()).isEqualTo("12345");
        assertThat(result.waitType()).isEqualTo("MEDIA");
    }

    @Test
    void rejectsMissingMedia() throws Exception {
        AiWorkflowNodeContext context = new AiWorkflowNodeContext(
            OBJECT_MAPPER.readTree("{\"type\":\"PLAY_MEDIA\",\"config\":{}}"),
            Map.of(), null, 1L, "VOICE");

        assertThatThrownBy(() -> handler.execute(context))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("未选择有效媒体");
    }
}
