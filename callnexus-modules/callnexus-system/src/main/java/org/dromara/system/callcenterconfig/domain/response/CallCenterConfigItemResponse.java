package org.dromara.system.callcenterconfig.domain.response;

import lombok.Data;

@Data
public class CallCenterConfigItemResponse {
    private String groupCode;
    private String groupName;
    private String configKey;
    private String configName;
    private String valueType;
    private String editorType;
    private String defaultValue;
    private String configValue;
    private String effectiveValue;
    private String source;
    private String unit;
    private String optionsJson;
    private String description;
    private String riskLevel;
    private Integer sortOrder;
}
