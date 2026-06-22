package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CallNoteRequest {

    @NotBlank(message = "通话备注不能为空")
    @Size(max = 1000, message = "通话备注不能超过 1000 个字符")
    private String content;
}
