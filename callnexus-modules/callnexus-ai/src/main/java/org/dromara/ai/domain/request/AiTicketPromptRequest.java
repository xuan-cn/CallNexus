package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiTicketPromptRequest {
    @NotBlank(message = "业务提示词不能为空")
    @Size(max = 30000, message = "业务提示词不能超过30000个字符")
    private String promptContent;
    @Size(max = 128, message = "版本说明不能超过128个字符")
    private String versionName;
}
