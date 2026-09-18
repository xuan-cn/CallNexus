package org.dromara.quality.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QualityAppealCreateRequest {
    @NotBlank
    @Size(max = 2000)
    private String appealReason;

    @Size(max = 2000)
    @Pattern(regexp = "^$|^[0-9]+(,[0-9]+){0,4}$", message = "申诉附件最多上传5个有效文件")
    private String attachmentOssIds;
}
