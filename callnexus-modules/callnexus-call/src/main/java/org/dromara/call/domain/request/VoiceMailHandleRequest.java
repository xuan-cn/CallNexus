package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VoiceMailHandleRequest {
    @NotBlank
    @Pattern(regexp = "^(UNHANDLED|HANDLED|INVALID)$")
    private String status;
    @Size(max = 500)
    private String handleRemark;
}
