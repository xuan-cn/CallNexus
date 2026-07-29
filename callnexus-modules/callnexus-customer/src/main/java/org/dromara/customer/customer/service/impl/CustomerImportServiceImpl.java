package org.dromara.customer.customer.service.impl;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.customer.customer.domain.request.CustomerImportData;
import org.dromara.customer.customer.domain.response.CustomerImportResponse;
import org.dromara.customer.customer.domain.vo.CustomerImportVo;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.customer.service.CustomerImportService;
import org.dromara.customer.customer.service.CustomerPhoneNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CustomerImportServiceImpl implements CustomerImportService {
    private static final int MAX_IMPORT_ROWS = 5000;
    private static final String PHONE_SEPARATOR = "[,，;；\\r\\n]+";

    private final CustomerApplicationService customerApplicationService;
    private final CustomerPhoneNormalizer phoneNormalizer;

    @Override
    public void downloadTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(List.of(), "客户导入模板", CustomerImportVo.class, response);
    }

    @Override
    public CustomerImportResponse importCustomers(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择需要导入的 Excel 文件");
        }
        List<CustomerImportVo> imports;
        try {
            imports = ExcelUtil.importExcel(file.getInputStream(), CustomerImportVo.class);
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Excel 文件读取失败，请下载并使用系统模板");
        }
        if (imports.isEmpty()) {
            throw new ServiceException("Excel 文件中没有可导入的客户数据");
        }
        if (imports.size() > MAX_IMPORT_ROWS) {
            throw new ServiceException("单次最多导入 " + MAX_IMPORT_ROWS + " 条客户数据");
        }

        CustomerImportResponse response = new CustomerImportResponse();
        response.setTotalCount(imports.size());
        Set<String> filePhones = new HashSet<>();
        for (int index = 0; index < imports.size(); index++) {
            CustomerImportResponse.Row row = importRow(index + 2, imports.get(index), filePhones);
            response.getRows().add(row);
            switch (row.getStatus()) {
                case "IMPORTED" -> response.setImportedCount(response.getImportedCount() + 1);
                case "SKIPPED" -> response.setSkippedCount(response.getSkippedCount() + 1);
                default -> response.setFailedCount(response.getFailedCount() + 1);
            }
        }
        return response;
    }

    private CustomerImportResponse.Row importRow(int rowNumber, CustomerImportVo source, Set<String> filePhones) {
        CustomerImportResponse.Row row = new CustomerImportResponse.Row();
        row.setRowNumber(rowNumber);
        row.setCustomerName(trim(source.getCustomerName()));
        row.setPrimaryPhone(trim(source.getPrimaryPhone()));
        try {
            CustomerImportData data = toImportData(source);
            Set<String> rowPhones = normalizedPhones(data);
            String duplicateFilePhone = rowPhones.stream().filter(filePhones::contains).findFirst().orElse(null);
            if (duplicateFilePhone != null) {
                row.setStatus("SKIPPED");
                row.setMessage("号码 " + duplicateFilePhone + " 在当前文件中重复");
                return row;
            }
            Long customerId = customerApplicationService.importCustomer(data);
            filePhones.addAll(rowPhones);
            row.setStatus("IMPORTED");
            row.setMessage("导入成功");
            row.setCustomerId(customerId);
        } catch (ServiceException exception) {
            String message = exception.getMessage();
            row.setStatus(isDuplicate(message) ? "SKIPPED" : "FAILED");
            row.setMessage(message);
        } catch (RuntimeException exception) {
            row.setStatus("FAILED");
            row.setMessage("导入失败，请检查该行数据");
        }
        return row;
    }

    private CustomerImportData toImportData(CustomerImportVo source) {
        CustomerImportData data = new CustomerImportData();
        data.setCustomerName(trim(source.getCustomerName()));
        data.setPrimaryPhone(phoneNormalizer.normalize(source.getPrimaryPhone()));
        String additionalPhones = trim(source.getAdditionalPhones());
        if (additionalPhones == null) {
            return data;
        }
        for (String token : additionalPhones.split(PHONE_SEPARATOR)) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String[] parts = token.trim().split("\\|", 2);
            CustomerImportData.Phone phone = new CustomerImportData.Phone();
            phone.setPhoneNumber(phoneNormalizer.normalize(parts[0]));
            phone.setPhoneLabel(parts.length > 1 ? trim(parts[1]) : null);
            data.getAdditionalPhones().add(phone);
        }
        return data;
    }

    private Set<String> normalizedPhones(CustomerImportData data) {
        Set<String> phones = new HashSet<>();
        if (!phones.add(data.getPrimaryPhone())) {
            throw new ServiceException("同一客户的电话号码不能重复");
        }
        for (CustomerImportData.Phone phone : data.getAdditionalPhones()) {
            if (!phones.add(phone.getPhoneNumber())) {
                throw new ServiceException("同一客户的电话号码不能重复：" + phone.getPhoneNumber());
            }
        }
        return phones;
    }

    private boolean isDuplicate(String message) {
        return message != null && (message.contains("已绑定") || message.contains("重复") || message.contains("已存在"));
    }

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
