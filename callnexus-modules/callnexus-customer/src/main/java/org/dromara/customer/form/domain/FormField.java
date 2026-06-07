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
@TableName("cc_form_field")
public class FormField extends TenantEntity {
    @TableId
    private Long id;
    private Long templateId;
    private String fieldCode;
    private String fieldName;
    private FormFieldType fieldType;
    private Boolean requiredFlag;
    private Integer sortOrder;
    private Integer layoutSpan;
    private String defaultValue;
    private String placeholderText;
    private String validationRules;
    private Boolean enabled;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
