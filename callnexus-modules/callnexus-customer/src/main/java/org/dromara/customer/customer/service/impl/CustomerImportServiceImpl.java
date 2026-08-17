package org.dromara.customer.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.domain.CustomerAssignment;
import org.dromara.customer.customer.domain.CustomerImportBatch;
import org.dromara.customer.customer.domain.CustomerImportRow;
import org.dromara.customer.customer.domain.CustomerImportTask;
import org.dromara.customer.customer.domain.request.CustomerImportBatchQuery;
import org.dromara.customer.customer.domain.request.CustomerImportData;
import org.dromara.customer.customer.domain.request.CustomerImportRequest;
import org.dromara.customer.customer.domain.request.CustomerImportRetryRequest;
import org.dromara.customer.customer.domain.response.CustomerImportAnalysisResponse;
import org.dromara.customer.customer.domain.response.CustomerImportBatchResponse;
import org.dromara.customer.customer.domain.vo.CustomerImportVo;
import org.dromara.customer.customer.mapper.CustomerAssignmentMapper;
import org.dromara.customer.customer.mapper.CustomerImportBatchMapper;
import org.dromara.customer.customer.mapper.CustomerImportRowMapper;
import org.dromara.customer.customer.mapper.CustomerImportTaskMapper;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.customer.service.CustomerImportService;
import org.dromara.customer.customer.service.CustomerPhoneNormalizer;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.dromara.common.tenant.helper.TenantHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerImportServiceImpl implements CustomerImportService {

    private static final int MAX_IMPORT_ROWS = 20000;
    private static final String PHONE_SEPARATOR = "[,，;；\\r\\n]+";

    private static final Set<String> NAME_HEADERS = Set.of(
        "姓名", "客户姓名", "客户名称", "名称", "名字", "name", "customername", "customer_name"
    );
    private static final Set<String> PHONE_HEADERS = Set.of(
        "手机号", "手机", "电话", "客户电话", "联系电话", "主号码", "号码", "phone", "mobile", "primaryphone", "primary_phone"
    );
    private static final Set<String> EXTRA_PHONE_HEADERS = Set.of(
        "其他号码", "备用号码", "多个号码", "附加号码", "additionalphones", "additional_phones"
    );
    private static final Set<String> TYPE_HEADERS = Set.of(
        "客户类型", "类型", "客户分类", "分类", "customer_type", "customertype"
    );
    private static final Set<String> SOURCE_HEADERS = Set.of(
        "来源", "来源渠道", "渠道", "source", "source_channel", "sourcechannel"
    );
    private static final Set<String> TAG_HEADERS = Set.of("标签", "客户标签", "tags", "tag");
    private static final Set<String> REMARK_HEADERS = Set.of("备注", "说明", "remark", "note");

    private final CustomerApplicationService customerApplicationService;
    private final CustomerPhoneNormalizer phoneNormalizer;
    private final CustomerImportBatchMapper batchMapper;
    private final CustomerImportRowMapper rowMapper;
    private final CustomerImportTaskMapper taskMapper;
    private final CustomerAssignmentMapper assignmentMapper;
    private final CustomerMapper customerMapper;
    private final DynamicFormSubmissionService dynamicFormSubmissionService;
    @Resource(name = "customerImportExecutor")
    private Executor customerImportExecutor;

    @Override
    public void downloadTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(List.of(), "客户导入模板", CustomerImportVo.class, response);
    }

    @Override
    public CustomerImportAnalysisResponse analyze(Long taskId, MultipartFile file) {
        requireTask(taskId, false);
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择需要分析的 Excel 文件");
        }
        return analyzeWorkbook(file);
    }

    @Override
    public CustomerImportBatchResponse startImport(Long taskId, MultipartFile file) {
        CustomerImportTask task = requireTask(taskId, true);
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择需要导入的 Excel 文件");
        }
        long running = batchMapper.selectCount(new LambdaQueryWrapper<CustomerImportBatch>()
            .eq(CustomerImportBatch::getTaskId, taskId)
            .in(CustomerImportBatch::getStatus, List.of("PENDING", "PROCESSING")));
        if (running > 0) {
            throw new ServiceException("当前任务已有导入批次正在执行");
        }
        CustomerImportRequest safeRequest = requestFromTask(task);
        List<ImportDraft> drafts = parse(file, safeRequest);
        if (drafts.isEmpty()) {
            throw new ServiceException("Excel 文件中没有可导入的客户数据");
        }
        if (drafts.size() > MAX_IMPORT_ROWS) {
            throw new ServiceException("单次最多导入 " + MAX_IMPORT_ROWS + " 行客户数据");
        }

        CustomerImportBatch batch = createBatch(taskId, file, safeRequest, drafts.size(), "PENDING");
        String tenantId = TenantHelper.getTenantId();
        customerImportExecutor.execute(() -> TenantHelper.dynamic(tenantId, () -> {
            try {
                processBatch(batch.getId(), drafts, safeRequest);
            } catch (Exception exception) {
                markBatchFailed(batch.getId(), exception);
            }
        }));
        return getBatch(taskId, batch.getId());
    }

    private void processBatch(Long batchId, List<ImportDraft> drafts, CustomerImportRequest safeRequest) {
        CustomerImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ServiceException("客户导入批次不存在");
        }
        batch.setStatus("PROCESSING");
        batchMapper.updateById(batch);

        Set<String> filePhones = new HashSet<>();
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (ImportDraft draft : drafts) {
            CustomerImportRow row = buildRow(batch, draft, safeRequest);
            try {
                importRow(row, draft, safeRequest, filePhones);
            } catch (Exception exception) {
                row.setStatus("FAILED");
                row.setErrorMessage(exception.getMessage());
            }
            rowMapper.insert(row);
            if ("IMPORTED".equals(row.getStatus())) {
                imported++;
            } else if ("SKIPPED".equals(row.getStatus())) {
                skipped++;
            } else {
                failed++;
            }
            batch.setImportedCount(imported);
            batch.setSkippedCount(skipped);
            batch.setFailedCount(failed);
            batchMapper.updateById(batch);
        }

        batch.setImportedCount(imported);
        batch.setSkippedCount(skipped);
        batch.setFailedCount(failed);
        batch.setStatus(failed == 0 ? "SUCCESS" : imported > 0 || skipped > 0 ? "PARTIAL_SUCCESS" : "FAILED");
        batchMapper.updateById(batch);
    }

    @Override
    public CustomerImportBatchResponse getBatch(Long taskId, Long batchId) {
        CustomerImportBatch batch = requireBatch(taskId, batchId);
        List<CustomerImportRow> rows = rowMapper.selectList(new LambdaQueryWrapper<CustomerImportRow>()
            .eq(CustomerImportRow::getBatchId, batchId)
            .orderByAsc(CustomerImportRow::getRowNumber));
        return toResponse(batch, rows);
    }

    @Override
    public TableDataInfo<CustomerImportBatchResponse> pageBatches(Long taskId, CustomerImportBatchQuery query, PageQuery pageQuery) {
        requireTask(taskId, false);
        CustomerImportBatchQuery safeQuery = query == null ? new CustomerImportBatchQuery() : query;
        LambdaQueryWrapper<CustomerImportBatch> wrapper = new LambdaQueryWrapper<CustomerImportBatch>()
            .eq(CustomerImportBatch::getTaskId, taskId)
            .like(!isBlank(safeQuery.getFileName()), CustomerImportBatch::getFileName, trim(safeQuery.getFileName()))
            .eq(!isBlank(safeQuery.getStatus()), CustomerImportBatch::getStatus, trim(safeQuery.getStatus()))
            .orderByDesc(CustomerImportBatch::getCreateTime);
        Page<CustomerImportBatch> page = batchMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream()
            .map(batch -> toResponse(batch, List.of()))
            .toList(), page.getTotal());
    }

    @Override
    public TableDataInfo<CustomerImportBatchResponse.Row> pageRows(
        Long taskId,
        Long batchId,
        String status,
        PageQuery pageQuery
    ) {
        requireBatch(taskId, batchId);
        LambdaQueryWrapper<CustomerImportRow> wrapper = new LambdaQueryWrapper<CustomerImportRow>()
            .eq(CustomerImportRow::getTaskId, taskId)
            .eq(CustomerImportRow::getBatchId, batchId)
            .eq(!isBlank(status), CustomerImportRow::getStatus, trim(status))
            .orderByAsc(CustomerImportRow::getRowNumber);
        Page<CustomerImportRow> page = rowMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toRowResponse).toList(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerImportBatchResponse retryRows(Long taskId, Long batchId, CustomerImportRetryRequest request) {
        CustomerImportBatch batch = requireNotRunning(taskId, batchId);
        List<Long> rowIds = request == null ? List.of() : request.getRowIds();
        LambdaQueryWrapper<CustomerImportRow> wrapper = new LambdaQueryWrapper<CustomerImportRow>()
            .eq(CustomerImportRow::getBatchId, batchId)
            .eq(CustomerImportRow::getStatus, "FAILED")
            .in(rowIds != null && !rowIds.isEmpty(), CustomerImportRow::getId, rowIds)
            .orderByAsc(CustomerImportRow::getRowNumber);
        List<CustomerImportRow> retryRows = rowMapper.selectList(wrapper);
        if (retryRows.isEmpty()) {
            throw new ServiceException("No failed rows to retry");
        }

        batch.setStatus("PROCESSING");
        batch.setFailureReason(null);
        batchMapper.updateById(batch);

        Set<String> filePhones = rowMapper.selectList(new LambdaQueryWrapper<CustomerImportRow>()
                .eq(CustomerImportRow::getBatchId, batchId)
                .ne(CustomerImportRow::getStatus, "FAILED"))
            .stream()
            .map(CustomerImportRow::getNormalizedPhone)
            .filter(phone -> !isBlank(phone))
            .collect(java.util.stream.Collectors.toSet());

        CustomerImportRequest importRequest = requestFromBatch(batch);
        for (CustomerImportRow row : retryRows) {
            row.setCustomerId(null);
            row.setStatus(null);
            row.setErrorMessage(null);
            try {
                importRow(row, new ImportDraft(row.getRowNumber(), Map.of(), Map.of(), Map.of()), importRequest, filePhones);
            } catch (Exception exception) {
                row.setStatus("FAILED");
                row.setErrorMessage(exception.getMessage());
            }
            rowMapper.updateById(row);
            if (!"FAILED".equals(row.getStatus()) && !isBlank(row.getNormalizedPhone())) {
                filePhones.add(row.getNormalizedPhone());
            }
            refreshBatchCounts(batchId, "PROCESSING");
        }
        refreshBatchCounts(batchId, null);
        return getBatch(taskId, batchId);
    }

    @Override
    public void downloadErrors(Long taskId, Long batchId, HttpServletResponse response) {
        requireBatch(taskId, batchId);
        List<CustomerImportErrorVo> rows = rowMapper.selectList(new LambdaQueryWrapper<CustomerImportRow>()
                .eq(CustomerImportRow::getBatchId, batchId)
                .ne(CustomerImportRow::getStatus, "IMPORTED")
                .orderByAsc(CustomerImportRow::getRowNumber))
            .stream()
            .map(this::toErrorVo)
            .toList();
        ExcelUtil.exportExcel(rows, "客户导入失败明细", CustomerImportErrorVo.class, response);
    }

    private CustomerImportBatch requireNotRunning(Long taskId, Long batchId) {
        CustomerImportBatch batch = requireBatch(taskId, batchId);
        if ("PENDING".equals(batch.getStatus()) || "PROCESSING".equals(batch.getStatus())) {
            throw new ServiceException("客户导入批次正在执行，暂不能操作");
        }
        return batch;
    }

    private CustomerImportRequest requestFromBatch(CustomerImportBatch batch) {
        CustomerImportRequest request = new CustomerImportRequest();
        request.setDuplicateStrategy(batch.getDuplicateStrategy());
        request.setDefaultCustomerType(batch.getDefaultCustomerType());
        request.setDefaultSourceChannel(batch.getDefaultSourceChannel());
        request.setDefaultTags(batch.getDefaultTags());
        request.setDefaultRemark(batch.getDefaultRemark());
        request.setFormTemplateId(batch.getFormTemplateId());
        request.setFieldMappingJson(batch.getFieldMappingJson());
        return request;
    }

    private CustomerImportRequest requestFromTask(CustomerImportTask task) {
        CustomerImportRequest request = new CustomerImportRequest();
        request.setDuplicateStrategy(defaultIfBlank(task.getDuplicateStrategy(), "SKIP"));
        request.setDefaultCustomerType(task.getDefaultCustomerType());
        request.setDefaultSourceChannel(task.getDefaultSourceChannel());
        request.setDefaultTags(task.getDefaultTags());
        request.setDefaultRemark(task.getDefaultRemark());
        request.setFormTemplateId(task.getFormTemplateId());
        request.setFieldMappingJson(task.getFieldMappingJson());
        return request;
    }

    private CustomerImportTask requireTask(Long taskId, boolean enabledRequired) {
        CustomerImportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("资料导入任务不存在");
        }
        if (enabledRequired && !"ENABLED".equals(task.getStatus())) {
            throw new ServiceException("资料导入任务已停用，不能继续上传");
        }
        return task;
    }

    private CustomerImportBatch requireBatch(Long taskId, Long batchId) {
        CustomerImportBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<CustomerImportBatch>()
            .eq(CustomerImportBatch::getId, batchId)
            .eq(CustomerImportBatch::getTaskId, taskId));
        if (batch == null) {
            throw new ServiceException("客户导入批次不存在");
        }
        return batch;
    }

    private void refreshBatchCounts(Long batchId, String forceStatus) {
        CustomerImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            return;
        }
        List<CustomerImportRow> rows = rowMapper.selectList(new LambdaQueryWrapper<CustomerImportRow>()
            .eq(CustomerImportRow::getBatchId, batchId));
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (CustomerImportRow row : rows) {
            if ("IMPORTED".equals(row.getStatus())) {
                imported++;
            } else if ("SKIPPED".equals(row.getStatus())) {
                skipped++;
            } else if ("FAILED".equals(row.getStatus())) {
                failed++;
            }
        }
        batch.setImportedCount(imported);
        batch.setSkippedCount(skipped);
        batch.setFailedCount(failed);
        if (!isBlank(forceStatus)) {
            batch.setStatus(forceStatus);
        } else {
            batch.setStatus(failed == 0 ? "SUCCESS" : imported > 0 || skipped > 0 ? "PARTIAL_SUCCESS" : "FAILED");
        }
        batchMapper.updateById(batch);
    }

    private CustomerImportBatch createBatch(
        Long taskId,
        MultipartFile file,
        CustomerImportRequest request,
        int totalCount,
        String status
    ) {
        CustomerImportBatch batch = new CustomerImportBatch();
        batch.setTaskId(taskId);
        batch.setFileName(file.getOriginalFilename());
        batch.setStatus(status);
        batch.setDuplicateStrategy(defaultIfBlank(request.getDuplicateStrategy(), "SKIP"));
        batch.setDefaultCustomerType(trim(request.getDefaultCustomerType()));
        batch.setDefaultSourceChannel(trim(request.getDefaultSourceChannel()));
        batch.setDefaultTags(trim(request.getDefaultTags()));
        batch.setDefaultRemark(trim(request.getDefaultRemark()));
        batch.setFormTemplateId(request.getFormTemplateId());
        batch.setFieldMappingJson(trim(request.getFieldMappingJson()));
        batch.setTotalCount(totalCount);
        batch.setImportedCount(0);
        batch.setSkippedCount(0);
        batch.setFailedCount(0);
        batchMapper.insert(batch);
        return batch;
    }

    private void markBatchFailed(Long batchId, Exception exception) {
        log.error("客户导入任务执行失败，batchId={}，error={}", batchId, exception.getMessage(), exception);
        CustomerImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            return;
        }
        batch.setStatus("FAILED");
        batch.setFailureReason(exception.getMessage());
        batchMapper.updateById(batch);
    }

    private CustomerImportRow buildRow(CustomerImportBatch batch, ImportDraft draft, CustomerImportRequest request) {
        CustomerImportRow row = new CustomerImportRow();
        row.setTaskId(batch.getTaskId());
        row.setBatchId(batch.getId());
        row.setRowNumber(draft.rowNumber());
        row.setCustomerName(defaultIfBlank(draft.value("name"), "未知客户"));
        row.setOriginalPhone(trim(draft.value("phone")));
        row.setNormalizedPhone(phoneNormalizer.normalize(row.getOriginalPhone()));
        row.setAdditionalPhones(trim(draft.value("additionalPhones")));
        row.setCustomerType(defaultIfBlank(draft.value("customerType"), request.getDefaultCustomerType()));
        row.setSourceChannel(defaultIfBlank(draft.value("sourceChannel"), request.getDefaultSourceChannel()));
        row.setTags(defaultIfBlank(draft.value("tags"), request.getDefaultTags()));
        row.setRawJson(JsonUtils.toJsonString(draft.raw()));
        return row;
    }

    private void importRow(CustomerImportRow row, ImportDraft draft, CustomerImportRequest request, Set<String> filePhones) {
        if (!phoneNormalizer.isValid(row.getNormalizedPhone())) {
            row.setStatus("FAILED");
            row.setErrorMessage("主号码无效");
            return;
        }
        if (!filePhones.add(row.getNormalizedPhone())) {
            row.setStatus("SKIPPED");
            row.setErrorMessage("同一导入文件中号码重复");
            return;
        }

        var existing = customerApplicationService.getByPhone(row.getNormalizedPhone());
        if (existing != null) {
            row.setCustomerId(Long.valueOf(String.valueOf(existing.getId())));
            if ("UPDATE".equals(defaultIfBlank(request.getDuplicateStrategy(), "SKIP"))) {
                Customer customer = customerMapper.selectById(row.getCustomerId());
                if (customer != null && !isBlank(row.getCustomerName())) {
                    customer.setCustomerName(row.getCustomerName());
                    customerMapper.updateById(customer);
                }
                saveDynamicForm(row, draft, request);
                row.setStatus("IMPORTED");
                row.setErrorMessage("客户已存在，已更新客户资料");
            } else {
                row.setStatus("SKIPPED");
                row.setErrorMessage("客户号码已存在，已跳过");
            }
            return;
        }

        CustomerImportData data = new CustomerImportData();
        data.setCustomerName(row.getCustomerName());
        data.setPrimaryPhone(row.getNormalizedPhone());
        for (String phone : splitPhones(row.getAdditionalPhones())) {
            if (phone.equals(row.getNormalizedPhone())) {
                continue;
            }
            CustomerImportData.Phone extra = new CustomerImportData.Phone();
            extra.setPhoneNumber(phone);
            extra.setPhoneLabel("导入号码");
            data.getAdditionalPhones().add(extra);
        }
        Long customerId = customerApplicationService.importCustomer(data);
        row.setCustomerId(customerId);
        saveImportMetadata(row, request.getDefaultRemark());
        saveDynamicForm(row, draft, request);
        row.setStatus("IMPORTED");
        row.setErrorMessage("导入成功");
    }

    private void saveDynamicForm(CustomerImportRow row, ImportDraft draft, CustomerImportRequest request) {
        if (request.getFormTemplateId() == null || draft.formValues().isEmpty()) {
            return;
        }
        dynamicFormSubmissionService.validateAndSave(
            request.getFormTemplateId(),
            FormBusinessType.CUSTOMER,
            row.getCustomerId(),
            new HashMap<>(draft.formValues())
        );
        customerMapper.update(null, new LambdaUpdateWrapper<Customer>()
            .eq(Customer::getId, row.getCustomerId())
            .set(Customer::getTemplateId, request.getFormTemplateId()));
    }

    private void saveImportMetadata(CustomerImportRow row, String remark) {
        boolean hasMetadata = !isBlank(row.getCustomerType())
            || !isBlank(row.getSourceChannel())
            || !isBlank(row.getTags());
        if (!hasMetadata && isBlank(remark)) {
            return;
        }
        CustomerAssignment assignment = new CustomerAssignment();
        assignment.setCustomerId(row.getCustomerId());
        assignment.setCustomerType(row.getCustomerType());
        assignment.setSourceChannel(row.getSourceChannel());
        assignment.setTags(row.getTags());
        assignment.setAssignmentSource("IMPORT_METADATA");
        assignment.setImportBatchId(row.getBatchId());
        assignment.setRemark(trim(remark));
        assignment.setEnabled(true);
        assignmentMapper.insert(assignment);
    }

    private CustomerImportAnalysisResponse analyzeWorkbook(MultipartFile file) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() <= 1) {
                throw new ServiceException("Excel 文件中没有可分析的数据");
            }
            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            Map<Integer, String> headers = readHeaders(headerRow, formatter);
            Map<Integer, String> fields = mapFields(headers, Map.of());

            CustomerImportAnalysisResponse response = new CustomerImportAnalysisResponse();
            response.setFileName(file.getOriginalFilename());
            response.setTotalRows(countDataRows(sheet));
            for (Map.Entry<Integer, String> entry : headers.entrySet()) {
                CustomerImportAnalysisResponse.Column column = new CustomerImportAnalysisResponse.Column();
                column.setHeader(entry.getValue());
                column.setSuggestedField(fields.get(entry.getKey()));
                response.getColumns().add(column);
            }
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum() && response.getSampleRows().size() < 5; rowIndex++) {
                Row sourceRow = sheet.getRow(rowIndex);
                if (sourceRow == null) {
                    continue;
                }
                Map<String, String> sample = new LinkedHashMap<>();
                boolean hasContent = false;
                for (Map.Entry<Integer, String> entry : headers.entrySet()) {
                    String value = formatter.formatCellValue(sourceRow.getCell(entry.getKey())).trim();
                    if (!value.isEmpty()) {
                        hasContent = true;
                    }
                    sample.put(entry.getValue(), value);
                }
                if (hasContent) {
                    response.getSampleRows().add(sample);
                }
            }
            return response;
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Excel 文件分析失败：" + exception.getMessage());
        }
    }

    private int countDataRows(Sheet sheet) {
        int count = 0;
        for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            if (sheet.getRow(rowIndex) != null) {
                count++;
            }
        }
        return count;
    }

    private List<ImportDraft> parse(MultipartFile file, CustomerImportRequest request) {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() <= 1) {
                return List.of();
            }
            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            Map<Integer, String> headers = readHeaders(headerRow, formatter);
            Map<Integer, String> fields = mapFields(headers, parseFieldMapping(request.getFieldMappingJson()));
            if (!fields.containsValue("phone")) {
                throw new ServiceException("Excel 必须包含手机号、电话、客户电话或号码列");
            }
            List<ImportDraft> drafts = new ArrayList<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row sourceRow = sheet.getRow(rowIndex);
                if (sourceRow == null) {
                    continue;
                }
                Map<String, String> values = new HashMap<>();
                Map<String, String> raw = new LinkedHashMap<>();
                boolean hasContent = false;
                for (Map.Entry<Integer, String> entry : headers.entrySet()) {
                    String value = formatter.formatCellValue(sourceRow.getCell(entry.getKey())).trim();
                    if (!value.isEmpty()) {
                        hasContent = true;
                    }
                    raw.put(entry.getValue(), value);
                    String field = fields.get(entry.getKey());
                    if (field != null && !value.isEmpty() && !field.startsWith("form:")) {
                        values.put(field, value);
                    }
                    if (field != null && !value.isEmpty() && field.startsWith("form:")) {
                        values.put(field, value);
                    }
                }
                if (hasContent) {
                    drafts.add(new ImportDraft(rowIndex + 1, fixedValues(values), formValues(values), raw));
                }
            }
            return drafts;
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Excel 文件解析失败：" + exception.getMessage());
        }
    }

    private Map<Integer, String> readHeaders(Row headerRow, DataFormatter formatter) {
        if (headerRow == null) {
            throw new ServiceException("Excel 第一行必须是表头");
        }
        Map<Integer, String> headers = new LinkedHashMap<>();
        for (Cell cell : headerRow) {
            String header = formatter.formatCellValue(cell).trim();
            if (!header.isEmpty()) {
                headers.put(cell.getColumnIndex(), header);
            }
        }
        return headers;
    }

    private Map<Integer, String> mapFields(Map<Integer, String> headers, Map<String, String> customMapping) {
        Map<Integer, String> fields = new HashMap<>();
        for (Map.Entry<Integer, String> entry : headers.entrySet()) {
            String field = customMapping.get(entry.getValue());
            if (field == null) {
                field = detectField(normalizeHeader(entry.getValue()));
            }
            if (field != null) {
                fields.put(entry.getKey(), field);
            }
        }
        return fields;
    }

    private Map<String, String> fixedValues(Map<String, String> values) {
        Map<String, String> fixed = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getKey().startsWith("form:")) {
                fixed.put(entry.getKey(), entry.getValue());
            }
        }
        return fixed;
    }

    private Map<String, Object> formValues(Map<String, String> values) {
        Map<String, Object> formValues = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith("form:") && entry.getKey().length() > 5) {
                formValues.put(entry.getKey().substring(5), entry.getValue());
            }
        }
        return formValues;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseFieldMapping(String mappingJson) {
        if (isBlank(mappingJson)) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = JsonUtils.parseObject(mappingJson, Map.class);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, String> mapping = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() != null && !isBlank(String.valueOf(entry.getValue()))) {
                    mapping.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            return mapping;
        } catch (RuntimeException exception) {
            throw new ServiceException("字段映射格式错误");
        }
    }

    private String detectField(String header) {
        if (NAME_HEADERS.contains(header)) return "name";
        if (PHONE_HEADERS.contains(header)) return "phone";
        if (EXTRA_PHONE_HEADERS.contains(header)) return "additionalPhones";
        if (TYPE_HEADERS.contains(header)) return "customerType";
        if (SOURCE_HEADERS.contains(header)) return "sourceChannel";
        if (TAG_HEADERS.contains(header)) return "tags";
        if (REMARK_HEADERS.contains(header)) return "remark";
        return null;
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("/", "").replace("\\", "");
    }

    private CustomerImportBatchResponse toResponse(CustomerImportBatch batch, List<CustomerImportRow> rows) {
        CustomerImportBatchResponse response = new CustomerImportBatchResponse();
        response.setTaskId(batch.getTaskId());
        response.setBatchId(batch.getId());
        response.setFileName(batch.getFileName());
        response.setStatus(batch.getStatus());
        response.setDuplicateStrategy(batch.getDuplicateStrategy());
        response.setDefaultCustomerType(batch.getDefaultCustomerType());
        response.setDefaultSourceChannel(batch.getDefaultSourceChannel());
        response.setDefaultTags(batch.getDefaultTags());
        response.setDefaultRemark(batch.getDefaultRemark());
        response.setTotalCount(nvl(batch.getTotalCount()));
        response.setImportedCount(nvl(batch.getImportedCount()));
        response.setSkippedCount(nvl(batch.getSkippedCount()));
        response.setFailedCount(nvl(batch.getFailedCount()));
        response.setFailureReason(batch.getFailureReason());
        response.setCreateTime(batch.getCreateTime());
        response.setRows(rows.stream().map(this::toRowResponse).toList());
        return response;
    }

    private CustomerImportBatchResponse.Row toRowResponse(CustomerImportRow source) {
        CustomerImportBatchResponse.Row row = new CustomerImportBatchResponse.Row();
        row.setId(source.getId());
        row.setRowNumber(nvl(source.getRowNumber()));
        row.setCustomerName(source.getCustomerName());
        row.setOriginalPhone(source.getOriginalPhone());
        row.setNormalizedPhone(source.getNormalizedPhone());
        row.setCustomerType(source.getCustomerType());
        row.setSourceChannel(source.getSourceChannel());
        row.setTags(source.getTags());
        row.setStatus(source.getStatus());
        row.setErrorMessage(source.getErrorMessage());
        row.setCustomerId(source.getCustomerId());
        return row;
    }

    private CustomerImportErrorVo toErrorVo(CustomerImportRow source) {
        CustomerImportErrorVo row = new CustomerImportErrorVo();
        row.setRowNumber(source.getRowNumber());
        row.setCustomerName(source.getCustomerName());
        row.setOriginalPhone(source.getOriginalPhone());
        row.setNormalizedPhone(source.getNormalizedPhone());
        row.setStatus(source.getStatus());
        row.setErrorMessage(source.getErrorMessage());
        return row;
    }

    private List<String> splitPhones(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        List<String> phones = new ArrayList<>();
        for (String token : value.split(PHONE_SEPARATOR)) {
            String normalized = phoneNormalizer.normalize(token);
            if (phoneNormalizer.isValid(normalized)) {
                phones.add(normalized);
            }
        }
        return phones;
    }

    private Long parseLong(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private String stringify(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? trim(defaultValue) : trim(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trim(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private record ImportDraft(int rowNumber, Map<String, String> values, Map<String, Object> formValues, Map<String, String> raw) {
        String value(String key) {
            return values.get(key);
        }
    }

    @Data
    public static class AssignmentRule {
        private String field;
        private String value;
        private Long skillGroupId;
        private Long agentId;

        boolean matches(CustomerImportRow row) {
            String rowValue = switch (defaultString(field)) {
                case "customerType" -> row.getCustomerType();
                case "sourceChannel" -> row.getSourceChannel();
                case "tags" -> row.getTags();
                default -> null;
            };
            if (isEmpty(rowValue) || isEmpty(value)) {
                return false;
            }
            if ("tags".equals(field)) {
                return rowValue.contains(value);
            }
            return rowValue.trim().equals(value.trim());
        }

        private String defaultString(String source) {
            return source == null ? "" : source;
        }

        private boolean isEmpty(String source) {
            return source == null || source.trim().isEmpty();
        }
    }

    @Data
    public static class CustomerImportErrorVo {
        @cn.idev.excel.annotation.ExcelProperty("行号")
        private Integer rowNumber;
        @cn.idev.excel.annotation.ExcelProperty("客户姓名")
        private String customerName;
        @cn.idev.excel.annotation.ExcelProperty("原始号码")
        private String originalPhone;
        @cn.idev.excel.annotation.ExcelProperty("清洗号码")
        private String normalizedPhone;
        @cn.idev.excel.annotation.ExcelProperty("状态")
        private String status;
        @cn.idev.excel.annotation.ExcelProperty("失败原因")
        private String errorMessage;
    }
}
