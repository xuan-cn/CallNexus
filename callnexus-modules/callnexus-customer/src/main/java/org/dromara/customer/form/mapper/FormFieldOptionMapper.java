package org.dromara.customer.form.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.customer.form.domain.FormFieldOption;
import org.apache.ibatis.annotations.Delete;

public interface FormFieldOptionMapper extends BaseMapperPlus<FormFieldOption, FormFieldOption> {
    @Delete("""
        DELETE option_item
        FROM cc_form_field_option option_item
        INNER JOIN cc_form_field field_item ON field_item.id = option_item.field_id
        WHERE field_item.template_id = #{templateId}
        """)
    int physicalDeleteByTemplateId(Long templateId);
}
