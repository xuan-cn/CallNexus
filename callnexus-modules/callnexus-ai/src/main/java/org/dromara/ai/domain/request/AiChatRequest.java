package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiChatRequest {
    private Long conversationId;
    @NotBlank @Size(max = 8000) private String message;
}
