package org.dromara.customer.customer.service;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.customer.customer.domain.response.CustomerImportResponse;
import org.springframework.web.multipart.MultipartFile;

public interface CustomerImportService {
    void downloadTemplate(HttpServletResponse response);

    CustomerImportResponse importCustomers(MultipartFile file);
}
