package org.dromara.resource.sip.domain.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BatchCreateSipAccountRequest {
    @NotNull
    private Long nodeId;

    @NotBlank
    @Pattern(regexp = "^[0-9]{2,16}$", message = "起始分机号必须为2到16位数字")
    private String startExtension;

    @NotNull
    @Min(value = 1, message = "新增数量不能小于1")
    @Max(value = 200, message = "单次最多新增200个分机")
    private Integer count;

    @NotBlank
    @Size(max = 48, message = "显示名称前缀不能超过48个字符")
    private String displayNamePrefix;

    @NotBlank
    @Size(min = 12, max = 128, message = "认证密码长度必须为12到128位")
    private String password;
}
