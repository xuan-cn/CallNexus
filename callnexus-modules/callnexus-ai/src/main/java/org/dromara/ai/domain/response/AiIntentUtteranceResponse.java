package org.dromara.ai.domain.response;

import lombok.Data;

@Data
public class AiIntentUtteranceResponse {
    private Long id;
    private String utteranceType;
    private String utteranceText;
}
