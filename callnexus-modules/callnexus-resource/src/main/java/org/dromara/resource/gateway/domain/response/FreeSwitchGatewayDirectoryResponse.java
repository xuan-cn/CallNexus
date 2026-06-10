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
}
