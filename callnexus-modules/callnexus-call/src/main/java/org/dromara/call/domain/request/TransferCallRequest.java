package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class TransferCallRequest {
    @NotBlank(message = "转接分机不能为空")
    @Pattern(regexp = "^[0-9*#+]{2,32}$", message = "转接分机格式不正确")
    private String targetExtension;
}
