package org.dromara.resource.phone.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdatePhoneNumberRequest {
    @NotBlank
    @Size(max = 32)
    @Pattern(regexp = "^[0-9+*#-]{1,32}$")
    private String number;
    @NotBlank
    @Size(max = 64)
    private String numberName;
    @NotBlank
    @Pattern(regexp = "^(DID|CALLER_ID|BOTH)$")
    private String numberType;
    @NotNull
    private Long nodeId;
    private Long gatewayId;
    @NotBlank
    @Pattern(regexp = "^(EXTENSION|IVR|NONE)$")
    private String routeType;
    @Size(max = 64)
    private String routeTarget;
    @NotNull
    private Boolean outboundDefault;
    @NotNull
    private Boolean enabled;
    @NotNull
    private Integer version;
}
