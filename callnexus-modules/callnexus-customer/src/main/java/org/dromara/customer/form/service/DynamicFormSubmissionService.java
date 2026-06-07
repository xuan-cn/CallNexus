package org.dromara.customer.form.service;

import org.dromara.customer.form.domain.FormBusinessType;

import java.util.Map;

public interface DynamicFormSubmissionService {
    void validateAndSave(Long templateId, FormBusinessType businessType, Long businessId, Map<String, Object> formData);

    Map<String, Object> getFormData(FormBusinessType businessType, Long businessId);
}
