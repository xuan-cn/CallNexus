package org.dromara.resource.sip.security.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SipRegistrationEventRequest {
    @NotBlank
    @Size(max = 20)
    private String tenantId;
    @NotBlank
    @Size(max = 96)
    private String eventKey;
    @NotNull
    private Long nodeId;
    @Size(max = 32)
    private String extension;
    @Size(max = 64)
    private String authUsername;
    @Size(max = 64)
    private String sourceIp;
    private Integer sourcePort;
    @Size(max = 16)
    private String transport;
    @Size(max = 255)
    private String userAgent;
    @NotBlank
    @Pattern(regexp = "SUCCESS|FAILURE", message = "注册结果只能是SUCCESS或FAILURE")
    private String result;
    @Size(max = 64)
    private String reasonCode;
    @NotNull
    private LocalDateTime occurredAt;
}
