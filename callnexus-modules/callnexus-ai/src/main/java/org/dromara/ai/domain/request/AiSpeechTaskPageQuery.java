package org.dromara.ai.domain.request;

import lombok.Data;

@Data
public class AiSpeechTaskPageQuery {
    private String taskType;
    private String businessType;
    private String status;
}
