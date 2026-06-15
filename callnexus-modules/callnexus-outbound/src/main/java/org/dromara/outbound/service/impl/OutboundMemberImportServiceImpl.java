package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.customer.customer.domain.request.CreateCustomerRequest;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.outbound.domain.OutboundImportBatch;
import org.dromara.outbound.domain.OutboundImportRow;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.response.OutboundImportBatchResponse;
import org.dromara.outbound.domain.response.OutboundImportRowResponse;
import org.dromara.outbound.domain.response.OutboundBlacklistMatch;
import org.dromara.outbound.domain.vo.OutboundMemberImportVo;
import org.dromara.outbound.domain.vo.OutboundImportErrorExportVo;
import org.dromara.outbound.mapper.OutboundImportBatchMapper;
import org.dromara.outbound.mapper.OutboundImportRowMapper;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.service.OutboundMemberImportService;
import org.dromara.outbound.service.OutboundBlacklistChecker;
import org.dromara.outbound.service.PhoneNumberNormalizer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OutboundMemberImportServiceImpl implements OutboundMemberImportService {
    private static final int MAX_IMPORT_ROWS = 5000;
    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundImportBatchMapper batchMapper;
    private final OutboundImportRowMapper rowMapper;
    private final CustomerApplicationService customerService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final OutboundBlacklistChecker blacklistChecker;

    @Override
    public void downloadTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(List.of(), "外呼名单", OutboundMemberImportVo.class, response);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundImportBatchResponse preview(Long taskId, MultipartFile file) {
        requireEditableTask(taskId);
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择需要导入的 Excel 文件");
        }
        List<OutboundMemberImportVo> imports;
        try {
            imports = ExcelUtil.importExcel(file.getInputStream(), OutboundMemberImportVo.class);
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Excel 文件读取失败，请使用系统模板并检查文件内容");
        }
        if (imports.isEmpty()) {
            throw new ServiceException("Excel 文件中没有可导入的数据");
        }
        if (imports.size() > MAX_IMPORT_ROWS) {
            throw new ServiceException("单次最多导入 " + MAX_IMPORT_ROWS + " 条外呼名单");
        }

        OutboundImportBatch batch = new OutboundImportBatch();
        batch.setTaskId(taskId);
        batch.setFileName(file.getOriginalFilename() == null ? "未命名文件.xlsx" : file.getOriginalFilename());
        batch.setStatus("PREVIEW");
        batch.setTotalCount(imports.size());
        batch.setValidCount(0);
        batch.setInvalidCount(0);
        batch.setDuplicateCount(0);
        batch.setImportedCount(0);
        batchMapper.insert(batch);

        Set<String> taskPhones = memberMapper.selectList(new LambdaQueryWrapper<OutboundMember>()
                .eq(OutboundMember::getTaskId, taskId))
            .stream().map(OutboundMember::getPhoneNumber).map(phoneNumberNormalizer::normalize)
            .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Set<String> filePhones = new HashSet<>();
        int validCount = 0;
        int invalidCount = 0;
        int duplicateCount = 0;
        for (int index = 0; index < imports.size(); index++) {
            OutboundMemberImportVo item = imports.get(index);
            String normalizedPhone = phoneNumberNormalizer.normalize(item.getPhoneNumber());
            OutboundImportRow row = new OutboundImportRow();
            row.setBatchId(batch.getId());
            row.setRowNumber(index + 2);
            row.setCustomerName(trimToNull(item.getCustomerName()));
            row.setOriginalPhone(trimToNull(item.getPhoneNumber()));
            row.setNormalizedPhone(normalizedPhone);
            if (!phoneNumberNormalizer.isValid(normalizedPhone)) {
                row.setStatus("INVALID");
                row.setErrorMessage("电话号码格式无效，清洗后应为 5 至 20 位数字，可包含开头的加号");
                invalidCount++;
            } else if (!filePhones.add(normalizedPhone)) {
                row.setStatus("DUPLICATE_FILE");
                row.setErrorMessage("同一文件中电话号码重复");
                duplicateCount++;
            } else if (taskPhones.contains(normalizedPhone)) {
                row.setStatus("DUPLICATE_TASK");
                row.setErrorMessage("该电话号码已存在于当前外呼任务");
                duplicateCount++;
            } else if (blacklistChecker.check(taskId, normalizedPhone) != null) {
                OutboundBlacklistMatch match = blacklistChecker.check(taskId, normalizedPhone);
                row.setStatus("BLACKLISTED");
                row.setErrorMessage("命中外呼黑名单" + (match.getReason() == null ? "" : "：" + match.getReason()));
            } else {
                CustomerResponse customer = customerService.getByPhone(normalizedPhone);
                row.setCustomerId(customer == null ? null : customer.getId());
                row.setStatus("VALID");
                validCount++;
            }
            rowMapper.insert(row);
        }
        batch.setValidCount(validCount);
        batch.setInvalidCount(invalidCount);
        batch.setDuplicateCount(duplicateCount);
        batchMapper.updateById(batch);
        return toResponse(batch, listRows(batch.getId()));
    }

    @Override
    public OutboundImportBatchResponse getBatch(Long taskId, Long batchId) {
        requireTask(taskId);
        OutboundImportBatch batch = requireBatch(taskId, batchId);
        return toResponse(batch, listRows(batchId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundImportBatchResponse confirm(Long taskId, Long batchId, boolean autoCreateCustomer) {
        requireEditableTask(taskId);
        OutboundImportBatch batch = requireBatch(taskId, batchId);
        if ("IMPORTED".equals(batch.getStatus())) {
            return toResponse(batch, listRows(batchId));
        }
        int locked = batchMapper.update(null, new LambdaUpdateWrapper<OutboundImportBatch>()
            .eq(OutboundImportBatch::getId, batchId)
            .eq(OutboundImportBatch::getStatus, "PREVIEW")
            .set(OutboundImportBatch::getStatus, "IMPORTING"));
        if (locked == 0) {
            throw new ServiceException("该导入批次正在处理，请勿重复确认");
        }
        int importedCount = 0;
        List<OutboundImportRow> rows = listRows(batchId);
        for (OutboundImportRow row : rows) {
            if (!"VALID".equals(row.getStatus())) {
                continue;
            }
            OutboundBlacklistMatch match = blacklistChecker.check(taskId, row.getNormalizedPhone());
            if (match != null) {
                row.setStatus("BLACKLISTED");
                row.setErrorMessage("确认导入时命中外呼黑名单" + (match.getReason() == null ? "" : "：" + match.getReason()));
                rowMapper.updateById(row);
                continue;
            }
            Long customerId = row.getCustomerId();
            if (customerId == null && autoCreateCustomer) {
                CreateCustomerRequest request = new CreateCustomerRequest();
                request.setPrimaryPhone(row.getNormalizedPhone());
                request.setCustomerName(row.getCustomerName());
                customerId = customerService.create(request);
                row.setCustomerId(customerId);
                rowMapper.updateById(row);
            }
            if (customerId == null) {
                row.setStatus("INVALID");
                row.setErrorMessage("未匹配到现有客户，且未开启自动创建客户");
                rowMapper.updateById(row);
                continue;
            }
            CustomerResponse customer = customerService.get(customerId);
            OutboundMember member = new OutboundMember();
            member.setTaskId(taskId);
            member.setCustomerId(customerId);
            member.setCustomerName(customer.getCustomerName());
            member.setPhoneNumber(row.getNormalizedPhone());
            member.setSourceType("EXCEL");
            member.setImportBatchId(batchId);
            member.setStatus("PENDING");
            member.setAttemptCount(0);
            try {
                memberMapper.insert(member);
                importedCount++;
            } catch (DuplicateKeyException ignored) {
                row.setStatus("DUPLICATE_TASK");
                row.setErrorMessage("确认导入时发现该客户已存在于当前外呼任务");
                rowMapper.updateById(row);
            }
        }
        rows = listRows(batchId);
        batch.setStatus("IMPORTED");
        batch.setImportedCount(importedCount);
        batch.setValidCount((int) rows.stream().filter(row -> "VALID".equals(row.getStatus())).count());
        batch.setInvalidCount((int) rows.stream().filter(row -> "INVALID".equals(row.getStatus())).count());
        batch.setDuplicateCount((int) rows.stream().filter(row -> row.getStatus().startsWith("DUPLICATE")).count());
        batchMapper.updateById(batch);
        return toResponse(batch, rows);
    }

    @Override
    public void downloadErrors(Long taskId, Long batchId, HttpServletResponse response) {
        requireBatch(taskId, batchId);
        List<OutboundImportErrorExportVo> errors = listRows(batchId).stream()
            .filter(row -> !"VALID".equals(row.getStatus()))
            .map(row -> {
                OutboundImportErrorExportVo item = new OutboundImportErrorExportVo();
                item.setRowNumber(row.getRowNumber());
                item.setCustomerName(row.getCustomerName());
                item.setOriginalPhone(row.getOriginalPhone());
                item.setNormalizedPhone(row.getNormalizedPhone());
                item.setStatus(statusLabel(row.getStatus()));
                item.setErrorMessage(row.getErrorMessage());
                return item;
            }).toList();
        ExcelUtil.exportExcel(errors, "导入失败明细", OutboundImportErrorExportVo.class, response);
    }

    private OutboundTask requireTask(Long taskId) {
        OutboundTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("外呼任务不存在");
        }
        return task;
    }

    private void requireEditableTask(Long taskId) {
        if ("RUNNING".equals(requireTask(taskId).getStatus())) {
            throw new ServiceException("执行中的外呼任务不能导入名单，请先暂停任务");
        }
    }

    private OutboundImportBatch requireBatch(Long taskId, Long batchId) {
        OutboundImportBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<OutboundImportBatch>()
            .eq(OutboundImportBatch::getId, batchId)
            .eq(OutboundImportBatch::getTaskId, taskId));
        if (batch == null) {
            throw new ServiceException("外呼名单导入批次不存在");
        }
        return batch;
    }

    private List<OutboundImportRow> listRows(Long batchId) {
        return rowMapper.selectList(new LambdaQueryWrapper<OutboundImportRow>()
            .eq(OutboundImportRow::getBatchId, batchId)
            .orderByAsc(OutboundImportRow::getRowNumber));
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "INVALID" -> "号码无效";
            case "DUPLICATE_FILE" -> "文件内重复";
            case "DUPLICATE_TASK" -> "任务内重复";
            case "BLACKLISTED" -> "黑名单拦截";
            default -> status;
        };
    }

    private OutboundImportBatchResponse toResponse(OutboundImportBatch batch, List<OutboundImportRow> rows) {
        OutboundImportBatchResponse response = new OutboundImportBatchResponse();
        response.setId(batch.getId());
        response.setTaskId(batch.getTaskId());
        response.setFileName(batch.getFileName());
        response.setStatus(batch.getStatus());
        response.setTotalCount(batch.getTotalCount());
        response.setValidCount(batch.getValidCount());
        response.setInvalidCount(batch.getInvalidCount());
        response.setDuplicateCount(batch.getDuplicateCount());
        response.setBlacklistedCount((int) rows.stream().filter(row -> "BLACKLISTED".equals(row.getStatus())).count());
        response.setImportedCount(batch.getImportedCount());
        response.setRows(rows.stream().map(this::toRowResponse).toList());
        return response;
    }

    private OutboundImportRowResponse toRowResponse(OutboundImportRow row) {
        OutboundImportRowResponse response = new OutboundImportRowResponse();
        response.setId(row.getId());
        response.setRowNumber(row.getRowNumber());
        response.setCustomerName(row.getCustomerName());
        response.setOriginalPhone(row.getOriginalPhone());
        response.setNormalizedPhone(row.getNormalizedPhone());
        response.setStatus(row.getStatus());
        response.setErrorMessage(row.getErrorMessage());
        response.setCustomerId(row.getCustomerId());
        return response;
    }
}
