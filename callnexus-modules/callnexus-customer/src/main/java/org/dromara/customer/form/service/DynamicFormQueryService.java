package org.dromara.customer.form.service;

import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.request.DynamicFieldFilter;

import java.util.List;

public interface DynamicFormQueryService {
    QueryCondition build(FormBusinessType businessType, Long templateId,
                         List<DynamicFieldFilter> filters, String outerTableName);

    record QueryCondition(String sql, Object[] parameters) {
        public boolean isEmpty() {
            return sql == null || sql.isBlank();
        }
    }
}
