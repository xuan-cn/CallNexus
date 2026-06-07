package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class OriginateCallRequest {
    @NotBlank
    @Pattern(regexp = "^[0-9*#+]{2,32}$")
    private String destination;
}
