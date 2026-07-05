package org.dromara.ai.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class AiKnowledgeFaqRequest {
    @NotBlank private String faqCode;
    @NotBlank private String faqName;
    @NotBlank @Size(max = 1000) private String standardQuestion;
    @NotBlank private String standardAnswer;
    private List<@NotBlank @Size(max = 1000) String> aliases;
    private String answerMode;
    private Boolean enabled;
    private Integer version;
}
