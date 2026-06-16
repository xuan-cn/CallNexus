package org.dromara.resource.phone.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.resource.businesshours.domain.request.PhoneBusinessHoursRouteRequest;

@Data
public class CreatePhoneNumberRequest {
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
    @Pattern(regexp = "^(EXTENSION|IVR|QUEUE|VOICEMAIL|BUSINESS_HOURS|NONE)$")
    private String routeType;
    @Size(max = 64)
    private String routeTarget;
    @NotNull
    private Boolean outboundDefault;
    private PhoneBusinessHoursRouteRequest businessHoursRoute;
}
