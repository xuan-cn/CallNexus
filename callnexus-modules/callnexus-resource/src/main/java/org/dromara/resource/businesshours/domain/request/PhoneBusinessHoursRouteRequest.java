package org.dromara.resource.businesshours.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PhoneBusinessHoursRouteRequest {
    @NotNull
    private Long planId;
    @NotBlank
    @Pattern(regexp = "^(EXTENSION|IVR|QUEUE|HANGUP)$")
    private String inHoursTargetType;
    @Size(max = 64)
    private String inHoursTarget;
    @NotBlank
    @Pattern(regexp = "^(EXTENSION|IVR|QUEUE|HANGUP)$")
    private String outHoursTargetType;
    @Size(max = 64)
    private String outHoursTarget;
}
