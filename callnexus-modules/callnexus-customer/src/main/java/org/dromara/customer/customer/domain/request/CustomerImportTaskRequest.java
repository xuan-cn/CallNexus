package org.dromara.customer.customer.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerImportTaskRequest {
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 128, message = "任务名称不能超过128个字符")
    private String taskName;
    @Size(max = 500, message = "任务说明不能超过500个字符")
    private String description;
    @Pattern(regexp = "SKIP|UPDATE", message = "重复号码策略不正确")
    private String duplicateStrategy = "SKIP";
    private Long formTemplateId;
    private String fieldMappingJson;
    private String defaultCustomerType;
    private String defaultSourceChannel;
    private String defaultTags;
    private String defaultRemark;
}
