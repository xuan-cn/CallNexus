package org.dromara.resource.gateway.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateFreeSwitchGatewayRequest {
    @NotNull
    private Long nodeId;
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$")
    private String gatewayCode;
    @NotBlank
    @Size(max = 64)
    private String gatewayName;
    @NotBlank
    @Pattern(regexp = "^(INBOUND|OUTBOUND|BOTH)$")
    private String direction;
    @NotBlank
    @Size(max = 128)
    private String proxy;
    @Size(max = 128)
    private String realm;
    @Size(max = 64)
    private String username;
    @Size(max = 128)
    private String password;
    @NotNull
    private Boolean registerEnabled;
    @NotBlank
    @Pattern(regexp = "^(UDP|TCP|TLS)$")
    private String transport;
    @Size(max = 32)
    private String callerIdNumber;
    @Min(0)
    @Max(3600)
    private Integer ping;
    @NotNull
    private Boolean enabled;
    @NotNull
    private Integer version;
}
