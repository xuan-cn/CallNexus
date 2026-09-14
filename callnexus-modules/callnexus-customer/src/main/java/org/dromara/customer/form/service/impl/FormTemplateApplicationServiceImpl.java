package org.dromara.customer.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormField;
import org.dromara.customer.form.domain.FormFieldOption;
import org.dromara.customer.form.domain.FormFieldType;
import org.dromara.customer.form.domain.FormQueryMode;
import org.dromara.customer.form.domain.FormTemplate;
import org.dromara.customer.form.domain.request.SaveFormTemplateRequest;
import org.dromara.customer.form.domain.response.FormTemplateResponse;
import org.dromara.customer.form.domain.response.FormQuerySchemaResponse;
import org.dromara.customer.form.mapper.FormFieldMapper;
import org.dromara.customer.form.mapper.FormFieldOptionMapper;
import org.dromara.customer.form.mapper.FormTemplateMapper;
import org.dromara.customer.form.service.FormTemplateApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
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
    public FormQuerySchemaResponse getQuerySchema(Long id) {
        FormTemplate template = requireTemplate(id);
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new ServiceException("表单模板已停用");
        }
        FormTemplateResponse templateResponse = toResponse(template);
        FormQuerySchemaResponse response = new FormQuerySchemaResponse();
        response.setTemplateId(template.getId());
        response.setTemplateName(template.getTemplateName());
        response.setBusinessType(template.getBusinessType());
        response.setFields(templateResponse.getFields().stream()
            .filter(field -> Boolean.TRUE.equals(field.getQueryEnabled()))
            .filter(field -> field.getFieldType() != FormFieldType.FILE)
            .sorted(Comparator.comparing(
                    (FormTemplateResponse.FieldResponse field) -> field.getQueryOrder() == null ? Integer.MAX_VALUE : field.getQueryOrder())
                .thenComparing(field -> field.getSortOrder() == null ? Integer.MAX_VALUE : field.getSortOrder()))
            .map(this::toQueryField)
            .toList());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SaveFormTemplateRequest request) {
        ensureCodeUnique(request.getTemplateCode(), null);
        validateFields(request.getBusinessType(), request.getFields());
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
        validateFields(request.getBusinessType(), request.getFields());
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
            field.setListVisible(Boolean.TRUE.equals(request.getListVisible()));
            field.setQueryEnabled(Boolean.TRUE.equals(request.getQueryEnabled()));
            field.setQueryMode(Boolean.TRUE.equals(request.getQueryEnabled())
                ? resolveQueryMode(request.getFieldType(), request.getQueryMode())
                : null);
            field.setQueryOrder(request.getQueryOrder() == null ? index : request.getQueryOrder());
            field.setFieldRemark(trimToNull(request.getFieldRemark()));
            field.setImportEnabled(request.getFieldType() != FormFieldType.FILE && defaultTrue(request.getImportEnabled()));
            field.setExportEnabled(defaultTrue(request.getExportEnabled()));
            field.setAiFillEnabled(request.getFieldType() != FormFieldType.FILE && defaultTrue(request.getAiFillEnabled()));
            field.setDialEnabled(Boolean.TRUE.equals(request.getDialEnabled()));
            field.setEnabled(defaultTrue(request.getEnabled()));
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

    private void validateFields(FormBusinessType businessType, List<SaveFormTemplateRequest.FieldRequest> fields) {
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
            if (Boolean.TRUE.equals(field.getQueryEnabled())) {
                if (field.getFieldType() == FormFieldType.FILE) {
                    throw new ServiceException("附件字段不支持列表查询");
                }
                resolveQueryMode(field.getFieldType(), field.getQueryMode());
            }
            if (field.getFieldType() == FormFieldType.FILE && Boolean.TRUE.equals(field.getImportEnabled())) {
                throw new ServiceException("附件字段不支持 Excel 资料导入");
            }
            if (field.getFieldType() == FormFieldType.FILE && Boolean.TRUE.equals(field.getAiFillEnabled())) {
                throw new ServiceException("附件字段不支持 AI 自动填写");
            }
            if (Boolean.TRUE.equals(field.getDialEnabled()) &&
                (businessType != FormBusinessType.CUSTOMER || field.getFieldType() != FormFieldType.INPUT)) {
                throw new ServiceException("只有客户表单的输入框字段可以启用点击拨号");
            }
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
        template.setWorkflowCode(request.getBusinessType() == FormBusinessType.TICKET
            ? normalizeWorkflowCode(request.getWorkflowCode()) : null);
        template.setEnabled(request.getEnabled());
    }

    private String normalizeWorkflowCode(String workflowCode) {
        return workflowCode == null || workflowCode.isBlank() ? null : workflowCode.trim();
    }

    private FormTemplateResponse toResponse(FormTemplate template) {
        FormTemplateResponse response = new FormTemplateResponse();
        response.setId(template.getId());
        response.setTemplateCode(template.getTemplateCode());
        response.setTemplateName(template.getTemplateName());
        response.setBusinessType(template.getBusinessType());
        response.setWorkflowCode(template.getWorkflowCode());
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
        response.setListVisible(Boolean.TRUE.equals(field.getListVisible()));
        response.setQueryEnabled(Boolean.TRUE.equals(field.getQueryEnabled()));
        response.setQueryMode(Boolean.TRUE.equals(field.getQueryEnabled())
            ? resolveQueryMode(field.getFieldType(), field.getQueryMode())
            : field.getQueryMode());
        response.setQueryOrder(field.getQueryOrder() == null ? field.getSortOrder() : field.getQueryOrder());
        response.setFieldRemark(field.getFieldRemark());
        response.setImportEnabled(defaultTrue(field.getImportEnabled()));
        response.setExportEnabled(defaultTrue(field.getExportEnabled()));
        response.setAiFillEnabled(defaultTrue(field.getAiFillEnabled()));
        response.setDialEnabled(Boolean.TRUE.equals(field.getDialEnabled()));
        response.setEnabled(defaultTrue(field.getEnabled()));
        response.setOptions(options.stream().map(this::toOptionResponse).toList());
        return response;
    }

    private FormQuerySchemaResponse.Field toQueryField(FormTemplateResponse.FieldResponse source) {
        FormQuerySchemaResponse.Field target = new FormQuerySchemaResponse.Field();
        target.setFieldCode(source.getFieldCode());
        target.setFieldName(source.getFieldName());
        target.setFieldType(source.getFieldType());
        target.setQueryMode(resolveQueryMode(source.getFieldType(), source.getQueryMode()));
        target.setQueryOrder(source.getQueryOrder());
        target.setPlaceholder(source.getPlaceholder());
        target.setFieldRemark(source.getFieldRemark());
        target.setOptions(source.getOptions().stream().map(option -> {
            FormQuerySchemaResponse.Option value = new FormQuerySchemaResponse.Option();
            value.setLabel(option.getLabel());
            value.setValue(option.getValue());
            return value;
        }).toList());
        return target;
    }

    private FormQueryMode resolveQueryMode(FormFieldType fieldType, FormQueryMode configured) {
        FormQueryMode mode = configured == null || configured == FormQueryMode.AUTO ? defaultQueryMode(fieldType) : configured;
        Set<FormQueryMode> supported = switch (fieldType) {
            case INPUT, TEXTAREA -> Set.of(FormQueryMode.EQ, FormQueryMode.LIKE);
            case RADIO, SELECT -> Set.of(FormQueryMode.EQ);
            case CHECKBOX, MULTI_SELECT -> Set.of(FormQueryMode.CONTAINS_ANY);
            case NUMBER, DATE, DATETIME -> Set.of(FormQueryMode.EQ, FormQueryMode.BETWEEN);
            case FILE -> Set.of();
        };
        if (!supported.contains(mode)) {
            throw new ServiceException("字段类型 " + fieldType + " 不支持查询方式 " + mode);
        }
        return mode;
    }

    private FormQueryMode defaultQueryMode(FormFieldType fieldType) {
        return switch (fieldType) {
            case INPUT, TEXTAREA -> FormQueryMode.LIKE;
            case RADIO, SELECT -> FormQueryMode.EQ;
            case CHECKBOX, MULTI_SELECT -> FormQueryMode.CONTAINS_ANY;
            case NUMBER, DATE, DATETIME -> FormQueryMode.BETWEEN;
            case FILE -> FormQueryMode.AUTO;
        };
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean defaultTrue(Boolean value) {
        return value == null || Boolean.TRUE.equals(value);
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
