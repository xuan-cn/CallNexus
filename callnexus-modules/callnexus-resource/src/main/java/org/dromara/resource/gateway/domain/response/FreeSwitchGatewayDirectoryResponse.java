package org.dromara.resource.gateway.domain.response;

import lombok.Data;

@Data
public class FreeSwitchGatewayDirectoryResponse {
    private Long id;
    private String domain;
    private String gatewayCode;
    private String proxy;
    private String realm;
    private String username;
    private String password;
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
}
