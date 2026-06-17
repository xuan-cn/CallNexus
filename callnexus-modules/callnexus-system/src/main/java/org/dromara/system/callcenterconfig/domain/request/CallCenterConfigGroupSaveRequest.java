package org.dromara.system.callcenterconfig.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CallCenterConfigGroupSaveRequest {
    @Valid
    @NotEmpty(message = "配置项不能为空")
    private List<Item> items;

    @Data
    public static class Item {
        @NotBlank(message = "配置键不能为空")
        private String configKey;
        private String configValue;
    }
}
