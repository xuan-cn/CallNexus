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
    private Integer ping;
    private Integer expireSeconds;
    private Integer retrySeconds;
    private Integer pingMax;
    private Integer pingMin;
    private Boolean callerIdInFrom;
    private String fromUser;
    private String fromDomain;
    private String contactParams;
    private String dialplanContext;
    private String extension;
    private String description;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
