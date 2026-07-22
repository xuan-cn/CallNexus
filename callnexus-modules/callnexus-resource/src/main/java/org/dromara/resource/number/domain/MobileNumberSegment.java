package org.dromara.resource.number.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_mobile_number_segment")
public class MobileNumberSegment extends TenantEntity {

    @TableId
    private Long id;

    private String countryCode;

    private String segmentPrefix;

    private String province;

    private String city;

    private String carrier;

    private Boolean enabled;

    @Version
    private Integer version;

    @TableLogic
    private Boolean deleted;
}
