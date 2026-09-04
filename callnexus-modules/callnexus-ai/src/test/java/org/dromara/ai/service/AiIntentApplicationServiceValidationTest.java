package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.service.impl.AiIntentApplicationServiceImpl;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class AiIntentApplicationServiceValidationTest {

    @Test
    void shouldRejectBlankRecognitionTextBeforeWritingLog() {
        AiIntentApplicationServiceImpl service = new AiIntentApplicationServiceImpl(
            null, null, null, null, null, null, null, null, null, null);
        AiIntentRecognitionRequest request = new AiIntentRecognitionRequest();
        request.setAgentId(1L);

        assertThatThrownBy(() -> service.recognize(request))
            .isInstanceOf(ServiceException.class)
            .hasMessage("意图识别文本不能为空");
    }
}
