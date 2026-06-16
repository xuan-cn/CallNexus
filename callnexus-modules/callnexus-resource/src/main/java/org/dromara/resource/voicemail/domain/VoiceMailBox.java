package org.dromara.resource.voicemail.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_voicemail_box")
public class VoiceMailBox extends TenantEntity {
    @TableId
    private Long id;
    private String boxCode;
    private String boxName;
    private Long promptMediaId;
    private Integer maxSeconds;
    private Integer silenceThreshold;
    private Integer silenceHits;
    private Boolean enabled;
    private String remark;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
