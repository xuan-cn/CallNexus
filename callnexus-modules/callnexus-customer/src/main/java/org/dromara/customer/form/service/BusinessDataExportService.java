package org.dromara.customer.form.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.form.domain.FormField;
import org.dromara.customer.form.domain.FormFieldOption;
import org.dromara.customer.form.mapper.FormFieldMapper;
import org.dromara.customer.form.mapper.FormFieldOptionMapper;
import org.dromara.customer.ticket.domain.response.TicketResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BusinessDataExportService {
    private final FormFieldMapper fieldMapper;
    private final FormFieldOptionMapper optionMapper;

    public void exportCustomers(List<CustomerResponse> rows, Long templateId, HttpServletResponse response) {
        List<Column<CustomerResponse>> fixed = List.of(
            new Column<>("客户姓名", CustomerResponse::getCustomerName),
            new Column<>("客户电话", CustomerResponse::getPrimaryPhone),
            new Column<>("客户类型", CustomerResponse::getCustomerType),
            new Column<>("来源渠道", CustomerResponse::getSourceChannel),
            new Column<>("标签", CustomerResponse::getTags),
            new Column<>("创建时间", CustomerResponse::getCreateTime)
        );
        export("客户资料", rows, fixed, templateId, CustomerResponse::getFormData, response);
    }

    public void exportTickets(List<TicketResponse> rows, Long templateId, HttpServletResponse response) {
        List<Column<TicketResponse>> fixed = List.of(
            new Column<>("工单编号", TicketResponse::getTicketNo),
            new Column<>("工单状态", TicketResponse::getTicketStatus),
            new Column<>("客户ID", TicketResponse::getCustomerId),
            new Column<>("来电号码", TicketResponse::getCallerNumber),
            new Column<>("创建时间", TicketResponse::getCreateTime)
        );
        export("工单资料", rows, fixed, templateId, TicketResponse::getFormData, response);
    }

    private <T> void export(String fileName, List<T> rows, List<Column<T>> fixedColumns, Long templateId,
                            Function<T, Map<String, Object>> formDataReader, HttpServletResponse response) {
        List<FormField> fields = exportFields(templateId);
        Map<Long, Map<String, String>> optionLabels = optionLabels(fields);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据");
            Row header = sheet.createRow(0);
            int columnIndex = 0;
            for (Column<T> column : fixedColumns) header.createCell(columnIndex++).setCellValue(column.name());
            for (FormField field : fields) header.createCell(columnIndex++).setCellValue(field.getFieldName());
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                T source = rows.get(rowIndex);
                Row target = sheet.createRow(rowIndex + 1);
                columnIndex = 0;
                for (Column<T> column : fixedColumns) setCell(target.createCell(columnIndex++), column.reader().apply(source));
                Map<String, Object> formData = formDataReader.apply(source);
                for (FormField field : fields) {
                    Object value = formData == null ? null : formData.get(field.getFieldCode());
                    setCell(target.createCell(columnIndex++), displayValue(value, optionLabels.get(field.getId())));
                }
            }
            for (int index = 0; index < fixedColumns.size() + fields.size(); index++) {
                sheet.setColumnWidth(index, 18 * 256);
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            String encoded = URLEncoder.encode(fileName + "_" + System.currentTimeMillis() + ".xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
            response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + encoded);
            workbook.write(response.getOutputStream());
        } catch (IOException exception) {
            throw new ServiceException("导出文件生成失败：" + exception.getMessage());
        }
    }

    private List<FormField> exportFields(Long templateId) {
        if (templateId == null) return List.of();
        return fieldMapper.selectList(new LambdaQueryWrapper<FormField>()
            .eq(FormField::getTemplateId, templateId)
            .eq(FormField::getEnabled, true)
            .eq(FormField::getExportEnabled, true)
            .orderByAsc(FormField::getSortOrder));
    }

    private Map<Long, Map<String, String>> optionLabels(List<FormField> fields) {
        List<Long> fieldIds = fields.stream().map(FormField::getId).toList();
        if (fieldIds.isEmpty()) return Map.of();
        return optionMapper.selectList(new LambdaQueryWrapper<FormFieldOption>()
                .in(FormFieldOption::getFieldId, fieldIds)
                .eq(FormFieldOption::getEnabled, true))
            .stream().collect(Collectors.groupingBy(FormFieldOption::getFieldId,
                Collectors.toMap(FormFieldOption::getOptionValue, FormFieldOption::getOptionLabel, (first, ignored) -> first)));
    }

    private Object displayValue(Object value, Map<String, String> optionLabels) {
        if (value == null) return "";
        if (value instanceof Collection<?> values) {
            return values.stream().map(item -> optionLabel(item, optionLabels)).collect(Collectors.joining("，"));
        }
        return optionLabel(value, optionLabels);
    }

    private String optionLabel(Object value, Map<String, String> optionLabels) {
        String text = String.valueOf(value);
        return optionLabels == null ? text : optionLabels.getOrDefault(text, text);
    }

    private void setCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof LocalDateTime dateTime) {
            cell.setCellValue(dateTime.toString().replace('T', ' '));
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private record Column<T>(String name, Function<T, Object> reader) {
    }
}
