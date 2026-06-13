package org.dromara.customer.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormField;
import org.dromara.customer.form.domain.FormFieldOption;
import org.dromara.customer.form.domain.FormFieldType;
import org.dromara.customer.form.domain.FormTemplate;
import org.dromara.customer.form.domain.request.SaveFormTemplateRequest;
import org.dromara.customer.form.domain.response.FormTemplateResponse;
import org.dromara.customer.form.mapper.FormFieldMapper;
import org.dromara.customer.form.mapper.FormFieldOptionMapper;
import org.dromara.customer.form.mapper.FormTemplateMapper;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FormTemplateApplicationServiceImpl implements FormTemplateApplicationService {
    private static final Set<FormFieldType> OPTION_FIELD_TYPES =
        Set.of(FormFieldType.RADIO, FormFieldType.CHECKBOX, FormFieldType.SELECT, FormFieldType.MULTI_SELECT);

    private final FormTemplateMapper templateMapper;
    private final FormFieldMapper fieldMapper;
    private final FormFieldOptionMapper optionMapper;

    @Override
    public List<FormTemplateResponse> list(FormBusinessType businessType) {
        return templateMapper.selectList(new LambdaQueryWrapper<FormTemplate>()
                .eq(businessType != null, FormTemplate::getBusinessType, businessType)
                .orderByAsc(FormTemplate::getBusinessType, FormTemplate::getTemplateCode))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public FormTemplateResponse get(Long id) {
        return toResponse(requireTemplate(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SaveFormTemplateRequest request) {
        ensureCodeUnique(request.getTemplateCode(), null);
        validateFields(request.getFields());
        FormTemplate template = new FormTemplate();
        applyTemplate(template, request);
        templateMapper.insert(template);
        replaceFields(template.getId(), request.getFields());
        return template.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SaveFormTemplateRequest request) {
        ensureCodeUnique(request.getTemplateCode(), id);
        validateFields(request.getFields());
        FormTemplate template = requireTemplate(id);
        applyTemplate(template, request);
        templateMapper.updateById(template);
        replaceFields(id, request.getFields());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireTemplate(id);
        deleteFields(id);
        templateMapper.deleteById(id);
    }

    private void replaceFields(Long templateId, List<SaveFormTemplateRequest.FieldRequest> requests) {
        deleteFields(templateId);
        for (int index = 0; index < requests.size(); index++) {
            SaveFormTemplateRequest.FieldRequest request = requests.get(index);
            FormField field = new FormField();
            field.setTemplateId(templateId);
            field.setFieldCode(request.getFieldCode());
            field.setFieldName(request.getFieldName());
            field.setFieldType(request.getFieldType());
            field.setRequiredFlag(request.getRequired());
            field.setSortOrder(request.getSortOrder() == null ? index : request.getSortOrder());
            field.setLayoutSpan(request.getLayoutSpan() == null ? 12 : request.getLayoutSpan());
            field.setDefaultValue(request.getDefaultValue());
            field.setPlaceholderText(request.getPlaceholder());
            field.setValidationRules(request.getValidationRules());
            field.setEnabled(true);
            fieldMapper.insert(field);
            for (int optionIndex = 0; optionIndex < request.getOptions().size(); optionIndex++) {
                SaveFormTemplateRequest.OptionRequest optionRequest = request.getOptions().get(optionIndex);
                FormFieldOption option = new FormFieldOption();
                option.setFieldId(field.getId());
                option.setOptionLabel(optionRequest.getLabel());
                option.setOptionValue(optionRequest.getValue());
                option.setSortOrder(optionRequest.getSortOrder() == null ? optionIndex : optionRequest.getSortOrder());
                option.setEnabled(true);
                optionMapper.insert(option);
            }
        }
    }

    private void deleteFields(Long templateId) {
        optionMapper.physicalDeleteByTemplateId(templateId);
        fieldMapper.physicalDeleteByTemplateId(templateId);
    }

    private void validateFields(List<SaveFormTemplateRequest.FieldRequest> fields) {
        if (fields == null) throw new ServiceException("请配置表单字段");
        long uniqueCodes = fields.stream().map(SaveFormTemplateRequest.FieldRequest::getFieldCode).distinct().count();
        if (uniqueCodes != fields.size()) throw new ServiceException("表单字段编码重复");
        for (SaveFormTemplateRequest.FieldRequest field : fields) {
            if (field.getOptions() == null) field.setOptions(List.of());
            if (OPTION_FIELD_TYPES.contains(field.getFieldType()) && field.getOptions().isEmpty()) {
                throw new ServiceException("请配置表单字段选项");
            }
            long uniqueOptions = field.getOptions().stream().map(SaveFormTemplateRequest.OptionRequest::getValue).distinct().count();
            if (uniqueOptions != field.getOptions().size()) throw new ServiceException("表单字段选项值重复");
        }
    }

    private void ensureCodeUnique(String code, Long excludedId) {
        boolean exists = templateMapper.exists(new LambdaQueryWrapper<FormTemplate>()
            .eq(FormTemplate::getTemplateCode, code)
            .ne(excludedId != null, FormTemplate::getId, excludedId));
        if (exists) throw new ServiceException("表单模板编码已存在");
    }

    private FormTemplate requireTemplate(Long id) {
        FormTemplate template = templateMapper.selectById(id);
        if (template == null) throw new ServiceException("表单模板不存在");
        return template;
    }

    private void applyTemplate(FormTemplate template, SaveFormTemplateRequest request) {
        template.setTemplateCode(request.getTemplateCode());
        template.setTemplateName(request.getTemplateName());
        template.setBusinessType(request.getBusinessType());
        template.setEnabled(request.getEnabled());
    }

    private FormTemplateResponse toResponse(FormTemplate template) {
        FormTemplateResponse response = new FormTemplateResponse();
        response.setId(template.getId());
        response.setTemplateCode(template.getTemplateCode());
        response.setTemplateName(template.getTemplateName());
        response.setBusinessType(template.getBusinessType());
        response.setEnabled(template.getEnabled());
        response.setVersion(template.getVersion());
        List<FormField> fields = fieldMapper.selectList(new LambdaQueryWrapper<FormField>()
            .eq(FormField::getTemplateId, template.getId()).orderByAsc(FormField::getSortOrder));
        List<Long> fieldIds = fields.stream().map(FormField::getId).toList();
        Map<Long, List<FormFieldOption>> options = fieldIds.isEmpty() ? Map.of() :
            optionMapper.selectList(new LambdaQueryWrapper<FormFieldOption>()
                    .in(FormFieldOption::getFieldId, fieldIds).orderByAsc(FormFieldOption::getSortOrder))
                .stream().collect(Collectors.groupingBy(FormFieldOption::getFieldId));
        response.setFields(fields.stream().map(field -> toFieldResponse(field, options.getOrDefault(field.getId(), List.of()))).toList());
        return response;
    }

    private FormTemplateResponse.FieldResponse toFieldResponse(FormField field, List<FormFieldOption> options) {
        FormTemplateResponse.FieldResponse response = new FormTemplateResponse.FieldResponse();
        response.setId(field.getId());
        response.setFieldCode(field.getFieldCode());
        response.setFieldName(field.getFieldName());
        response.setFieldType(field.getFieldType());
        response.setRequired(field.getRequiredFlag());
        response.setSortOrder(field.getSortOrder());
        response.setLayoutSpan(field.getLayoutSpan() == null ? 12 : field.getLayoutSpan());
        response.setDefaultValue(field.getDefaultValue());
        response.setPlaceholder(field.getPlaceholderText());
        response.setValidationRules(field.getValidationRules());
        response.setOptions(options.stream().map(this::toOptionResponse).toList());
        return response;
    }

    private FormTemplateResponse.OptionResponse toOptionResponse(FormFieldOption option) {
        FormTemplateResponse.OptionResponse response = new FormTemplateResponse.OptionResponse();
        response.setId(option.getId());
        response.setLabel(option.getOptionLabel());
        response.setValue(option.getOptionValue());
        response.setSortOrder(option.getSortOrder());
        return response;
    }
}
