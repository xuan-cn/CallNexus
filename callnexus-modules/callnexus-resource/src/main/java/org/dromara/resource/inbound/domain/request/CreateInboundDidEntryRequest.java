package org.dromara.resource.inbound.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateInboundDidEntryRequest {
    @NotNull(message = "请选择 FreeSWITCH 节点")
    private Long nodeId;
    @NotNull(message = "请选择网关")
    private Long gatewayId;
    private Long phoneNumberId;
    @NotBlank(message = "请输入入口名称")
    @Size(max = 64, message = "入口名称不能超过 64 个字符")
    private String entryName;
    @NotBlank(message = "请选择入口类型")
    private String entryType;
    @Size(max = 64, message = "DID号码不能超过 64 个字符")
    private String didNumber;
    @Size(max = 64, message = "端口标识不能超过 64 个字符")
    private String portCode;
    @Size(max = 64, message = "账号标识不能超过 64 个字符")
    private String accountCode;
    @Size(max = 128, message = "Header 名称不能超过 128 个字符")
    private String headerName;
    @Size(max = 255, message = "Header 值不能超过 255 个字符")
    private String headerValue;
    @NotBlank(message = "请选择路由目标类型")
    private String routeTargetType;
    @NotBlank(message = "请选择路由目标")
    private String routeTargetId;
    private Integer priority;
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
