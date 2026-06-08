package org.dromara.customer.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormField;
import org.dromara.customer.form.domain.FormFieldOption;
import org.dromara.customer.form.domain.FormFieldType;
import org.dromara.customer.form.domain.FormSubmission;
import org.dromara.customer.form.domain.FormTemplate;
import org.dromara.customer.form.mapper.FormFieldMapper;
import org.dromara.customer.form.mapper.FormFieldOptionMapper;
import org.dromara.customer.form.mapper.FormSubmissionMapper;
import org.dromara.customer.form.mapper.FormTemplateMapper;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DynamicFormSubmissionServiceImpl implements DynamicFormSubmissionService {
    private static final Set<FormFieldType> OPTION_TYPES =
        Set.of(FormFieldType.RADIO, FormFieldType.CHECKBOX, FormFieldType.SELECT, FormFieldType.MULTI_SELECT);
    private static final Set<FormFieldType> MULTI_VALUE_TYPES =
        Set.of(FormFieldType.CHECKBOX, FormFieldType.MULTI_SELECT);

    private final FormTemplateMapper templateMapper;
    private final FormFieldMapper fieldMapper;
    private final FormFieldOptionMapper optionMapper;
    private final FormSubmissionMapper submissionMapper;

    @Override
    public void validateAndSave(Long templateId, FormBusinessType businessType, Long businessId, Map<String, Object> formData) {
        if (templateId == null) return;
        FormTemplate template = templateMapper.selectById(templateId);
        if (template == null || !Boolean.TRUE.equals(template.getEnabled()) || template.getBusinessType() != businessType) {
            throw new ServiceException("FORM_TEMPLATE_NOT_FOUND_OR_DISABLED");
        }
        Map<String, Object> values = formData == null ? Map.of() : formData;
        List<FormField> fields = fieldMapper.selectList(new LambdaQueryWrapper<FormField>()
            .eq(FormField::getTemplateId, templateId).eq(FormField::getEnabled, true));
        Set<String> fieldCodes = fields.stream().map(FormField::getFieldCode).collect(Collectors.toSet());
        if (!fieldCodes.containsAll(values.keySet())) throw new ServiceException("FORM_DATA_CONTAINS_UNKNOWN_FIELD");
        for (FormField field : fields) validateField(field, values.get(field.getFieldCode()));

        FormSubmission submission = submissionMapper.selectOne(new LambdaQueryWrapper<FormSubmission>()
            .eq(FormSubmission::getBusinessType, businessType)
            .eq(FormSubmission::getBusinessId, businessId)
            .last("LIMIT 1"));
        boolean creating = submission == null;
        if (creating) submission = new FormSubmission();
        submission.setTemplateId(templateId);
        submission.setBusinessType(businessType);
        submission.setBusinessId(businessId);
        submission.setFormData(JsonUtils.toJsonString(values));
        if (creating) {
            submissionMapper.insert(submission);
        } else {
            submissionMapper.updateById(submission);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFormData(FormBusinessType businessType, Long businessId) {
        FormSubmission submission = submissionMapper.selectOne(new LambdaQueryWrapper<FormSubmission>()
            .eq(FormSubmission::getBusinessType, businessType)
            .eq(FormSubmission::getBusinessId, businessId)
            .orderByDesc(FormSubmission::getCreateTime)
            .last("LIMIT 1"));
        if (submission == null) return Map.of();
        Map<String, Object> data = JsonUtils.parseObject(submission.getFormData(), Map.class);
        return data == null ? Map.of() : data;
    }

    private void validateField(FormField field, Object value) {
        if (Boolean.TRUE.equals(field.getRequiredFlag()) && isEmpty(value)) {
            throw new ServiceException("错误，请检查必填字段是否填写:" + field.getFieldName());
        }
        if (isEmpty(value) || !OPTION_TYPES.contains(field.getFieldType())) return;
        Set<String> allowedValues = optionMapper.selectList(new LambdaQueryWrapper<FormFieldOption>()
                .eq(FormFieldOption::getFieldId, field.getId()).eq(FormFieldOption::getEnabled, true))
            .stream().map(FormFieldOption::getOptionValue).collect(Collectors.toSet());
        Collection<?> submittedValues = MULTI_VALUE_TYPES.contains(field.getFieldType())
            ? requireCollection(value, field.getFieldCode()) : List.of(value);
        if (submittedValues.stream().map(String::valueOf).anyMatch(item -> !allowedValues.contains(item))) {
            throw new ServiceException("FORM_FIELD_OPTION_INVALID:" + field.getFieldName());
        }
    }

    private Collection<?> requireCollection(Object value, String fieldCode) {
        if (value instanceof Collection<?> collection) return collection;
        throw new ServiceException("FORM_FIELD_REQUIRES_ARRAY:" + fieldCode);
    }

    private boolean isEmpty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
            || value instanceof Collection<?> collection && collection.isEmpty();
    }
}
