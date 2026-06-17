package org.dromara.system.callcenterconfig.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cc_callcenter_config_definition")
public class CallCenterConfigDefinition {
    @TableId
    private Long id;
    private String groupCode;
    private String groupName;
    private String configKey;
    private String configName;
    private String valueType;
    private String editorType;
    private String defaultValue;
    private String unit;
    private String optionsJson;
    private String description;
    private String riskLevel;
    private Integer sortOrder;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
