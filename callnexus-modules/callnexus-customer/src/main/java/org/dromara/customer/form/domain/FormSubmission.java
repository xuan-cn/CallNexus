package org.dromara.customer.form.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_form_submission")
public class FormSubmission extends TenantEntity {
    @TableId
    private Long id;
    private Long templateId;
    private FormBusinessType businessType;
    private Long businessId;
    private String formData;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
