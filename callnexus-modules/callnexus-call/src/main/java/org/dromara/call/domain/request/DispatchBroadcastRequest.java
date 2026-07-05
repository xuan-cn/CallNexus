package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DispatchBroadcastRequest {
    @NotNull(message = "广播媒体不能为空")
    private Long mediaAssetId;

    @NotEmpty(message = "广播目标分机不能为空")
    @Size(max = 50, message = "单次广播最多选择50个分机")
    private List<@Pattern(regexp = "^[0-9*#+]{2,32}$", message = "目标分机格式不正确") String> targetExtensions;
}
