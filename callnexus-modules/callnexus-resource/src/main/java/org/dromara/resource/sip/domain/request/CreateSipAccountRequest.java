package org.dromara.resource.sip.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSipAccountRequest {
    @NotNull
    private Long nodeId;
    @NotBlank
    @Pattern(regexp = "^[0-9]{2,16}$", message = "分机号必须为2到16位数字")
    private String extension;
    @Size(max = 64)
    @Pattern(regexp = "^[A-Za-z0-9_.-]{4,64}$", message = "SIP 鉴权名只能包含字母、数字、下划线、点和横线，长度 4 到 64 位")
    private String authUsername;
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
