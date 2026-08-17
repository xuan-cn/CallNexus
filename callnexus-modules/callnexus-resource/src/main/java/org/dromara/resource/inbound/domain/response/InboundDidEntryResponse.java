package org.dromara.resource.inbound.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class InboundDidEntryResponse {
    private Long id;
    private Long nodeId;
    private String nodeName;
    private Long gatewayId;
    private String gatewayName;
    private String gatewayCode;
    private String entryName;
    private String entryType;
    private String didNumber;
    private String portCode;
    private String accountCode;
    private String headerName;
    private String headerValue;
    private String routeTargetType;
    private String routeTargetId;
    private String routeTargetName;
    private Integer priority;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
}
