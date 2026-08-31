package org.dromara.customer.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.service.AiTicketBusinessContextProvider;
import org.dromara.ai.service.model.AiTicketTemplateContext;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.form.domain.FormField;
import org.dromara.customer.form.domain.FormFieldOption;
import org.dromara.customer.form.domain.FormTemplate;
import org.dromara.customer.form.mapper.FormFieldMapper;
import org.dromara.customer.form.mapper.FormFieldOptionMapper;
import org.dromara.customer.form.mapper.FormTemplateMapper;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AiTicketBusinessContextProviderImpl implements AiTicketBusinessContextProvider {
    private final FormTemplateMapper templateMapper;
    private final FormFieldMapper fieldMapper;
    private final FormFieldOptionMapper optionMapper;
    private final CustomerMapper customerMapper;
    private final TicketMapper ticketMapper;

    @Override
    public AiTicketTemplateContext load(Long ticketTemplateId, String callerNumber) {
        FormTemplate template = templateMapper.selectById(ticketTemplateId);
        if (template == null || !Boolean.TRUE.equals(template.getEnabled())) {
            throw new ServiceException("自动工单关联的表单模板不存在或已停用");
        }
        List<FormField> fields = fieldMapper.selectList(new LambdaQueryWrapper<FormField>()
            .eq(FormField::getTemplateId, ticketTemplateId)
            .eq(FormField::getEnabled, true)
            .orderByAsc(FormField::getSortOrder));
        List<Long> fieldIds = fields.stream().map(FormField::getId).toList();
        Map<Long, List<String>> options = fieldIds.isEmpty() ? Map.of() : optionMapper.selectList(
                new LambdaQueryWrapper<FormFieldOption>().in(FormFieldOption::getFieldId, fieldIds)
                    .eq(FormFieldOption::getEnabled, true).orderByAsc(FormFieldOption::getSortOrder)).stream()
            .collect(Collectors.groupingBy(FormFieldOption::getFieldId,
                Collectors.mapping(FormFieldOption::getOptionValue, Collectors.toList())));
        List<AiTicketTemplateContext.Field> definitions = fields.stream().map(field ->
            new AiTicketTemplateContext.Field(field.getFieldCode(), field.getFieldName(), field.getFieldType().name(),
                Boolean.TRUE.equals(field.getRequiredFlag()), field.getDefaultValue(),
                options.getOrDefault(field.getId(), List.of()))).toList();
        Customer customer = StringUtils.isBlank(callerNumber) ? null : customerMapper.selectOne(
            new LambdaQueryWrapper<Customer>().eq(Customer::getPrimaryPhone, callerNumber).last("LIMIT 1"));
        String profile = customer == null ? "未识别客户" : "客户姓名：" +
            StringUtils.blankToDefault(customer.getCustomerName(), "未命名") + "；联系电话：" + customer.getPrimaryPhone();
        return new AiTicketTemplateContext(customer == null ? null : customer.getId(), profile,
            template.getTemplateName(), definitions);
    }

    @Override
    public boolean hasFormalTicket(String businessCallId) {
        return StringUtils.isNotBlank(businessCallId) && ticketMapper.selectCount(
            new LambdaQueryWrapper<Ticket>().eq(Ticket::getSourceCallId, businessCallId)) > 0;
    }
}
