package org.dromara.ai.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SpeechProviderTestResponse {
    private String testType;
    private String status;
    private String message;
    private long durationMs;
}
