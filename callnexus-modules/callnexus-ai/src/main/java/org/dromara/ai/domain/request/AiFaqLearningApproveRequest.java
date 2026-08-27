package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AiFaqLearningApproveRequest {
    @NotBlank @Size(max = 64) private String faqCode;
    @NotBlank @Size(max = 128) private String faqName;
    @NotBlank @Size(max = 1000) private String standardQuestion;
    @NotBlank private String standardAnswer;
    private List<String> aliases;
    private String answerMode;
}
