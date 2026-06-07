package org.dromara.resource.node.domain.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateFreeSwitchNodeRequest {
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$")
    private String nodeCode;
    @NotBlank
    @Size(max = 64)
    private String nodeName;
    @NotBlank
    @Size(max = 128)
    private String sipDomain;
    @NotBlank
    @Pattern(regexp = "^wss://.+$")
    @Size(max = 255)
    private String wssUrl;
    @NotBlank
    @Size(max = 128)
    private String eslHost;
    @Min(1)
    @Max(65535)
    private Integer eslPort;
    @NotBlank
    @Size(max = 128)
    private String eslPassword;
}
