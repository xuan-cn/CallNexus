package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CallConferenceInviteRequest {
    @NotBlank(message = "目标分机不能为空")
    @Pattern(regexp = "^[A-Za-z0-9._*#+-]{1,64}$", message = "目标分机格式不正确")
    private String targetExtension;
}
