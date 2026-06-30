package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DispatchGroupCallRequest {
    @NotEmpty(message = "组呼目标分机不能为空")
    @Size(max = 50, message = "单次组呼最多选择50个分机")
    private List<@Pattern(regexp = "^[0-9*#+]{2,32}$", message = "目标分机格式不正确") String> targetExtensions;
}
