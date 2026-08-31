package org.dromara.ai.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AiTicketPromptValidationResponse {
    private boolean valid;
    private List<String> errors;
    private String compiledPreview;
}
