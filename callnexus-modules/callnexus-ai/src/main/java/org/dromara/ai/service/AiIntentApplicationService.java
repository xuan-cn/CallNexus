package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.request.AiIntentRequest;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.domain.response.AiIntentResponse;

import java.util.List;

public interface AiIntentApplicationService {
    List<AiIntentResponse> intents();
    AiIntentResponse intent(Long id);
    Long createIntent(AiIntentRequest request);
    void updateIntent(Long id, AiIntentRequest request);
    void deleteIntent(Long id);
    AiIntentRecognitionResponse recognize(AiIntentRecognitionRequest request);
}
