package org.dromara.customer.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormField;
import org.dromara.customer.form.domain.FormFieldOption;
import org.dromara.customer.form.domain.FormFieldType;
import org.dromara.customer.form.domain.FormQueryMode;
import org.dromara.customer.form.domain.FormTemplate;
import org.dromara.customer.form.domain.request.DynamicFieldFilter;
import org.dromara.customer.form.mapper.FormFieldMapper;
import org.dromara.customer.form.mapper.FormFieldOptionMapper;
import org.dromara.customer.form.mapper.FormTemplateMapper;
import org.dromara.customer.form.service.DynamicFormQueryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicFormQueryServiceImpl implements DynamicFormQueryService {
    private static final int MAX_FILTERS = 8;
    private static final int MAX_MULTI_VALUES = 20;
    private static final Set<String> OUTER_TABLES = Set.of("cc_customer", "cc_ticket");

    private final FormTemplateMapper templateMapper;
    private final FormFieldMapper fieldMapper;
    private final FormFieldOptionMapper optionMapper;

    @Override
    public QueryCondition build(FormBusinessType businessType, Long templateId,
                                List<DynamicFieldFilter> filters, String outerTableName) {
        long startNanos = System.nanoTime();
        List<DynamicFieldFilter> effectiveFilters = filters == null ? List.of() : filters.stream()
            .filter(this::hasValue)
            .toList();
        if (effectiveFilters.isEmpty()) return new QueryCondition("", new Object[0]);
        if (templateId == null) throw new ServiceException("使用动态条件查询时必须选择表单模板");
        if (!OUTER_TABLES.contains(outerTableName)) throw new ServiceException("动态查询业务表不受支持");
        if (effectiveFilters.size() > MAX_FILTERS) throw new ServiceException("动态查询条件最多配置8个");

        FormTemplate template = templateMapper.selectById(templateId);
        if (template == null || !Boolean.TRUE.equals(template.getEnabled()) || template.getBusinessType() != businessType) {
            throw new ServiceException("表单模板不存在、已停用或业务类型不匹配");
        }
        Map<String, FormField> fields = fieldMapper.selectList(new LambdaQueryWrapper<FormField>()
                .eq(FormField::getTemplateId, templateId)
                .eq(FormField::getEnabled, true)
                .eq(FormField::getQueryEnabled, true))
            .stream().collect(Collectors.toMap(FormField::getFieldCode, field -> field));
        Map<Long, Set<String>> options = loadOptions(fields.values());

        SqlBuilder sql = new SqlBuilder();
        List<String> predicates = new ArrayList<>();
        for (DynamicFieldFilter filter : effectiveFilters) {
            FormField field = fields.get(filter.getFieldCode());
            if (field == null) throw new ServiceException("字段未开放查询：" + filter.getFieldCode());
            FormQueryMode mode = resolveMode(field);
            if (filter.getOperator() != null && !filter.getOperator().isBlank()) {
                FormQueryMode requested = parseMode(filter.getOperator());
                if (requested != mode) throw new ServiceException("字段查询方式与模板配置不一致：" + field.getFieldName());
            }
            predicates.add(buildPredicate(sql, field, mode, filter, options.getOrDefault(field.getId(), Set.of())));
        }

        String condition = "EXISTS (SELECT 1 FROM cc_form_submission submission"
            + " WHERE submission.tenant_id = " + outerTableName + ".tenant_id"
            + " AND submission.business_id = " + outerTableName + ".id"
            + " AND submission.business_type = " + sql.param(businessType.name())
            + " AND submission.template_id = " + sql.param(templateId)
            + " AND submission.deleted = 0 AND " + String.join(" AND ", predicates) + ")";
        QueryCondition result = new QueryCondition(condition, sql.parameters());
        log.debug("动态表单查询条件构造完成，businessType={}, templateId={}, filterCount={}, elapsedMs={}",
            businessType, templateId, effectiveFilters.size(), (System.nanoTime() - startNanos) / 1_000_000);
        return result;
    }

    private String buildPredicate(SqlBuilder sql, FormField field, FormQueryMode mode,
                                  DynamicFieldFilter filter, Set<String> options) {
        String json = "JSON_EXTRACT(submission.form_data, " + sql.param("$." + field.getFieldCode()) + ")";
        return switch (mode) {
            case EQ -> {
                Object value = normalizeScalar(field, filter.getValue());
                validateOption(field, value, options);
                yield scalarExpression(field, json) + " = " + sql.param(value);
            }
            case LIKE -> "JSON_UNQUOTE(" + json + ") LIKE "
                + sql.param("%" + escapeLike(filter.getValue().toString().trim()) + "%") + " ESCAPE '\\\\'";
            case BETWEEN -> {
                Object start = normalizeScalar(field, filter.getValue());
                Object end = normalizeScalar(field, filter.getEndValue());
                if (end == null) throw new ServiceException("范围查询缺少结束值：" + field.getFieldName());
                String expression = scalarExpression(field, json);
                yield expression + " BETWEEN " + sql.param(start) + " AND " + sql.param(end);
            }
            case CONTAINS_ANY -> {
                Collection<?> values = requireCollection(filter.getValue(), field.getFieldName());
                if (values.isEmpty() || values.size() > MAX_MULTI_VALUES) {
                    throw new ServiceException("多选查询值数量必须在1到20之间：" + field.getFieldName());
                }
                List<String> contains = values.stream().map(value -> {
                    String normalized = String.valueOf(value);
                    validateOption(field, normalized, options);
                    return "JSON_CONTAINS(" + json + ", JSON_QUOTE(" + sql.param(normalized) + "))";
                }).toList();
                yield "(" + String.join(" OR ", contains) + ")";
            }
            case AUTO -> throw new ServiceException("查询方式未解析：" + field.getFieldName());
        };
    }

    private String scalarExpression(FormField field, String json) {
        return switch (field.getFieldType()) {
            case NUMBER -> "CAST(JSON_UNQUOTE(" + json + ") AS DECIMAL(30,8))";
            case DATE -> "CAST(JSON_UNQUOTE(" + json + ") AS DATE)";
            case DATETIME -> "CAST(JSON_UNQUOTE(" + json + ") AS DATETIME)";
            default -> "JSON_UNQUOTE(" + json + ")";
        };
    }

    private Object normalizeScalar(FormField field, Object value) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new ServiceException("查询值不能为空：" + field.getFieldName());
        }
        String text = String.valueOf(value).trim();
        try {
            return switch (field.getFieldType()) {
                case NUMBER -> new BigDecimal(text);
                case DATE -> LocalDate.parse(text);
                case DATETIME -> LocalDateTime.parse(text.replace(' ', 'T'));
                default -> text;
            };
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw new ServiceException("查询值格式不正确：" + field.getFieldName());
        }
    }

    private void validateOption(FormField field, Object value, Set<String> options) {
        if (EnumSet.of(FormFieldType.RADIO, FormFieldType.SELECT, FormFieldType.CHECKBOX, FormFieldType.MULTI_SELECT)
            .contains(field.getFieldType()) && !options.contains(String.valueOf(value))) {
            throw new ServiceException("查询选项值不合法：" + field.getFieldName());
        }
    }

    private Map<Long, Set<String>> loadOptions(Collection<FormField> fields) {
        List<Long> fieldIds = fields.stream().map(FormField::getId).toList();
        if (fieldIds.isEmpty()) return Map.of();
        return optionMapper.selectList(new LambdaQueryWrapper<FormFieldOption>()
                .in(FormFieldOption::getFieldId, fieldIds)
                .eq(FormFieldOption::getEnabled, true))
            .stream().collect(Collectors.groupingBy(FormFieldOption::getFieldId,
                HashMap::new, Collectors.mapping(FormFieldOption::getOptionValue, Collectors.toSet())));
    }

    private FormQueryMode resolveMode(FormField field) {
        if (field.getQueryMode() != null && field.getQueryMode() != FormQueryMode.AUTO) return field.getQueryMode();
        return switch (field.getFieldType()) {
            case INPUT, TEXTAREA -> FormQueryMode.LIKE;
            case RADIO, SELECT -> FormQueryMode.EQ;
            case CHECKBOX, MULTI_SELECT -> FormQueryMode.CONTAINS_ANY;
            case NUMBER, DATE, DATETIME -> FormQueryMode.BETWEEN;
            case FILE -> throw new ServiceException("附件字段不支持查询");
        };
    }

    private FormQueryMode parseMode(String operator) {
        try {
            return FormQueryMode.valueOf(operator.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("不支持的动态查询方式");
        }
    }

    private Collection<?> requireCollection(Object value, String fieldName) {
        if (value instanceof Collection<?> collection) return collection;
        throw new ServiceException("多选查询值必须是数组：" + fieldName);
    }

    private boolean hasValue(DynamicFieldFilter filter) {
        if (filter == null || filter.getFieldCode() == null || filter.getFieldCode().isBlank()) return false;
        Object value = filter.getValue();
        return value != null && (!(value instanceof String text) || !text.isBlank())
            && (!(value instanceof Collection<?> collection) || !collection.isEmpty());
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static final class SqlBuilder {
        private final List<Object> parameters = new ArrayList<>();

        private String param(Object value) {
            int index = parameters.size();
            parameters.add(value);
            return "{" + index + "}";
        }

        private Object[] parameters() {
            return parameters.toArray();
        }
    }
}
