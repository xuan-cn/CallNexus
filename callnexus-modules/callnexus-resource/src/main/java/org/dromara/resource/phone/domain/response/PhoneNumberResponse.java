package org.dromara.resource.phone.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class PhoneNumberResponse {
    private Long id;
    private String number;
    private String numberName;
    private String numberType;
    private Long nodeId;
    private String nodeName;
    private Long gatewayId;
    private String gatewayName;
    private String routeType;
    private String routeTarget;
    private Boolean outboundDefault;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
