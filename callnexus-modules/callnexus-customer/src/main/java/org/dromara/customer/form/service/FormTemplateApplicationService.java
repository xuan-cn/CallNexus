package org.dromara.customer.form.service;

import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.request.SaveFormTemplateRequest;
import org.dromara.customer.form.domain.response.FormTemplateResponse;

import java.util.List;

public interface FormTemplateApplicationService {
    List<FormTemplateResponse> list(FormBusinessType businessType);
    FormTemplateResponse get(Long id);
    Long create(SaveFormTemplateRequest request);
    void update(Long id, SaveFormTemplateRequest request);
    void delete(Long id);
}
