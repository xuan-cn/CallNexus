package org.dromara.ai.domain.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
@Data
public class AiFaqCandidateUpdateRequest {
    @NotBlank @Size(max=64) private String faqCode;
    @NotBlank @Size(max=128) private String faqName;
    @NotBlank @Size(max=1000) private String standardQuestion;
    @NotBlank private String standardAnswer;
    private List<String> aliases;
    private String answerMode;
    private Integer version;
}
