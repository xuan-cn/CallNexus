package org.dromara.customer.customer.service;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.customer.domain.request.CustomerImportBatchQuery;
import org.dromara.customer.customer.domain.request.CustomerImportRetryRequest;
import org.dromara.customer.customer.domain.response.CustomerImportAnalysisResponse;
import org.dromara.customer.customer.domain.response.CustomerImportBatchResponse;
import org.springframework.web.multipart.MultipartFile;

public interface CustomerImportService {
    void downloadTemplate(HttpServletResponse response);

    CustomerImportAnalysisResponse analyze(Long taskId, MultipartFile file);

    CustomerImportBatchResponse startImport(Long taskId, MultipartFile file);

    CustomerImportBatchResponse getBatch(Long taskId, Long batchId);

    TableDataInfo<CustomerImportBatchResponse> pageBatches(Long taskId, CustomerImportBatchQuery query, PageQuery pageQuery);

    TableDataInfo<CustomerImportBatchResponse.Row> pageRows(Long taskId, Long batchId, String status, PageQuery pageQuery);

    CustomerImportBatchResponse retryRows(Long taskId, Long batchId, CustomerImportRetryRequest request);

    void downloadErrors(Long taskId, Long batchId, HttpServletResponse response);
}
