package org.dromara.resource.sip.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSipAccountRequest {
    @NotBlank
    @Pattern(regexp = "^[0-9]{2,16}$", message = "分机号必须为2到16位数字")
    private String extension;
    @NotBlank
    @Size(max = 64)
    private String displayName;
    @NotBlank
    @Size(max = 128)
    private String domain;
    @NotBlank
    @Size(min = 12, max = 128)
    private String password;
}
