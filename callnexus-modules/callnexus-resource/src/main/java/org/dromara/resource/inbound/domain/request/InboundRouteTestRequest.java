package org.dromara.resource.inbound.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InboundRouteTestRequest {
    @NotNull(message = "请选择 FreeSWITCH 节点")
    private Long nodeId;
    private Long gatewayId;
    @Size(max = 64, message = "主叫号码不能超过 64 个字符")
    private String callerNumber;
    @NotBlank(message = "请输入被叫号码或入口标识")
    @Size(max = 64, message = "被叫号码不能超过 64 个字符")
    private String calledNumber;
    @Size(max = 128, message = "来源 IP 不能超过 128 个字符")
    private String sourceIp;
    @Size(max = 64, message = "端口标识不能超过 64 个字符")
    private String portCode;
    @Size(max = 64, message = "账号标识不能超过 64 个字符")
    private String accountCode;
    @Size(max = 128, message = "Header 名称不能超过 128 个字符")
    private String headerName;
    @Size(max = 255, message = "Header 值不能超过 255 个字符")
    private String headerValue;
}
