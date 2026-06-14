package org.dromara.ivr.domain;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class IvrFlowVersionResponse {
    private Long id;
    private Long flowId;
    private Integer versionNo;
    private String graphJson;
    private String status;
    private LocalDateTime publishedAt;
    private Integer nodeCount;
    private Integer edgeCount;
    private Map<String, Integer> nodeTypeCounts;
    private Boolean currentVersion;
}
