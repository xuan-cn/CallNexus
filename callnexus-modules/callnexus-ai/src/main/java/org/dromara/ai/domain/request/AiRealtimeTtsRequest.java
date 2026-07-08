package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiRealtimeTtsRequest {
    @NotBlank(message = "租户ID不能为空")
    private String tenantId;

    @Size(max = 64, message = "音色长度不能超过64个字符")
    private String voice;

    private Integer sampleRate;

    private String format;

    @NotBlank(message = "合成文本不能为空")
    @Size(max = 2000, message = "合成文本不能超过2000个字符")
    private String text;
}
