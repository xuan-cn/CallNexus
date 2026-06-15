package org.dromara.outbound.service;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.outbound.domain.response.OutboundImportBatchResponse;
import org.springframework.web.multipart.MultipartFile;

public interface OutboundMemberImportService {
    void downloadTemplate(HttpServletResponse response);
    OutboundImportBatchResponse preview(Long taskId, MultipartFile file);
    OutboundImportBatchResponse getBatch(Long taskId, Long batchId);
    OutboundImportBatchResponse confirm(Long taskId, Long batchId, boolean autoCreateCustomer);
    void downloadErrors(Long taskId, Long batchId, HttpServletResponse response);
}
