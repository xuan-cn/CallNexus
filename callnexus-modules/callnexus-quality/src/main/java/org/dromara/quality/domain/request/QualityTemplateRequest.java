package org.dromara.quality.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class QualityTemplateRequest {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,32}$") private String templateCode;
    @NotBlank @Size(max = 64) private String templateName;
    @NotBlank private String directionScope;
    @NotNull @Min(1) private Integer totalScore;
    @NotNull @Min(0) private Integer qualifiedScore;
    private Boolean aiReviewEnabled = false;
    @Size(max = 500) private String remark;
    private Integer version;
    @NotEmpty private List<@Valid QualityTemplateItemRequest> items;
}
