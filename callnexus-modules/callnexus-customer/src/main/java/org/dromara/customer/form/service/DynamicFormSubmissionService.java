package org.dromara.customer.form.service;

import org.dromara.customer.form.domain.FormBusinessType;

import java.util.Collection;
import java.util.Map;

public interface DynamicFormSubmissionService {
    void validateAndSave(Long templateId, FormBusinessType businessType, Long businessId, Map<String, Object> formData);

    Map<String, Object> getFormData(FormBusinessType businessType, Long businessId);

    Map<Long, Map<String, Object>> getFormData(FormBusinessType businessType, Collection<Long> businessIds);

    Long getLatestTemplateId(FormBusinessType businessType, Long businessId);

    void delete(FormBusinessType businessType, Long businessId);
}
