package org.dromara.customer.form.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.customer.form.domain.FormField;
import org.apache.ibatis.annotations.Delete;

public interface FormFieldMapper extends BaseMapperPlus<FormField, FormField> {
    @Delete("DELETE FROM cc_form_field WHERE template_id = #{templateId}")
    int physicalDeleteByTemplateId(Long templateId);
}
