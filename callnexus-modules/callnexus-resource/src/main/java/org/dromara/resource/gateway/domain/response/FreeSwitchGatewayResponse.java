package org.dromara.resource.gateway.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class FreeSwitchGatewayResponse {
    private Long id;
    private Long nodeId;
    private String nodeName;
    private String gatewayCode;
    private String gatewayName;
    private String direction;
    private String proxy;
    private String realm;
    private String username;
    private Boolean registerEnabled;
    private String transport;
    private String callerIdNumber;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
