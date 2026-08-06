package org.dromara.openapi.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiEventClusterMessage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long applicationId;
    private String payload;
    private String sourceInstanceId;
}
