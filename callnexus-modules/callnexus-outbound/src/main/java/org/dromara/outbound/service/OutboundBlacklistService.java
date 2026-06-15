package org.dromara.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.outbound.domain.*;
import org.dromara.outbound.domain.request.OutboundBlacklistRequest;
import org.dromara.outbound.domain.response.OutboundBlacklistImportResponse;
import org.dromara.outbound.domain.response.OutboundBlacklistResponse;
import org.dromara.outbound.domain.vo.OutboundBlacklistImportErrorVo;
import org.dromara.outbound.domain.vo.OutboundBlacklistImportVo;
import org.dromara.outbound.mapper.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OutboundBlacklistService {
    private static final Set<String> SCOPES = Set.of("GLOBAL", "TASK");
    private static final Set<String> SOURCES = Set.of("MANUAL", "EXCEL", "CUSTOMER_REQUEST", "SYSTEM_RULE");
    private static final int MAX_IMPORT_ROWS = 5000;

    private final OutboundBlacklistMapper blacklistMapper;
    private final OutboundBlacklistImportBatchMapper batchMapper;
    private final OutboundBlacklistImportRowMapper rowMapper;
    private final OutboundTaskMapper taskMapper;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final OutboundBlacklistMemberSyncService memberSyncService;

    public List<OutboundBlacklistResponse> list() {
        memberSyncService.restoreExpired();
        return blacklistMapper.selectList(new LambdaQueryWrapper<OutboundBlacklist>()
                .orderByDesc(OutboundBlacklist::getCreateTime))
            .stream().map(this::response).toList();
    }

    public OutboundBlacklistResponse get(Long id) {
        memberSyncService.restoreExpired();
        return response(require(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(OutboundBlacklistRequest request) {
        OutboundBlacklist blacklist = new OutboundBlacklist();
        apply(blacklist, request);
        try {
            blacklistMapper.insert(blacklist);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("该范围内已存在相同电话号码的黑名单记录");
        }
        memberSyncService.blockMembers(blacklist);
        return blacklist.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, OutboundBlacklistRequest request) {
        OutboundBlacklist blacklist = require(id);
        memberSyncService.restore(id);
        apply(blacklist, request);
        try {
            blacklistMapper.updateById(blacklist);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("该范围内已存在相同电话号码的黑名单记录");
        }
        memberSyncService.blockMembers(blacklist);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        OutboundBlacklist blacklist = require(id);
        memberSyncService.restore(id);
        blacklistMapper.deleteById(blacklist);
    }

    @Transactional(rollbackFor = Exception.class)
    public void enable(Long id) {
        OutboundBlacklist blacklist = require(id);
        blacklist.setEnabled(true);
        blacklistMapper.updateById(blacklist);
        memberSyncService.blockMembers(blacklist);
    }

    @Transactional(rollbackFor = Exception.class)
    public void disable(Long id) {
        OutboundBlacklist blacklist = require(id);
        blacklist.setEnabled(false);
        blacklistMapper.updateById(blacklist);
        memberSyncService.restore(id);
    }

    public void downloadTemplate(HttpServletResponse response) {
        ExcelUtil.exportExcel(List.of(), "外呼黑名单", OutboundBlacklistImportVo.class, response);
    }

    @Transactional(rollbackFor = Exception.class)
    public OutboundBlacklistImportResponse preview(String scopeType, Long taskId, MultipartFile file) {
        validateScope(scopeType, taskId);
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择需要导入的 Excel 文件");
        }
        List<OutboundBlacklistImportVo> imports;
        try {
            imports = ExcelUtil.importExcel(file.getInputStream(), OutboundBlacklistImportVo.class);
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Excel 文件读取失败，请使用系统模板并检查文件内容");
        }
        if (imports.isEmpty()) {
            throw new ServiceException("Excel 文件中没有可导入的数据");
        }
        if (imports.size() > MAX_IMPORT_ROWS) {
            throw new ServiceException("单次最多导入 " + MAX_IMPORT_ROWS + " 条黑名单");
        }
        OutboundBlacklistImportBatch batch = new OutboundBlacklistImportBatch();
        batch.setScopeType(scopeType);
        batch.setTaskId(taskId);
        batch.setFileName(file.getOriginalFilename() == null ? "未命名文件.xlsx" : file.getOriginalFilename());
        batch.setStatus("PREVIEW");
        batch.setTotalCount(imports.size());
        batch.setValidCount(0);
        batch.setInvalidCount(0);
        batch.setDuplicateCount(0);
        batch.setImportedCount(0);
        batchMapper.insert(batch);

        Set<String> filePhones = new HashSet<>();
        int valid = 0;
        int invalid = 0;
        int duplicate = 0;
        for (int index = 0; index < imports.size(); index++) {
            OutboundBlacklistImportVo item = imports.get(index);
            String normalized = phoneNumberNormalizer.normalize(item.getPhoneNumber());
            OutboundBlacklistImportRow row = new OutboundBlacklistImportRow();
            row.setBatchId(batch.getId());
            row.setRowNumber(index + 2);
            row.setOriginalPhone(trim(item.getPhoneNumber()));
            row.setNormalizedPhone(normalized);
            row.setReason(trim(item.getReason()));
            if (!phoneNumberNormalizer.isValid(normalized)) {
                row.setStatus("INVALID");
                row.setErrorMessage("电话号码格式无效，标准化后应为 5 至 20 位数字，可包含开头的加号");
                invalid++;
            } else if (!filePhones.add(normalized)) {
                row.setStatus("DUPLICATE_FILE");
                row.setErrorMessage("同一文件中电话号码重复");
                duplicate++;
            } else if (exists(scopeType, taskId, normalized, null)) {
                row.setStatus("DUPLICATE_EXISTING");
                row.setErrorMessage("该范围内已存在相同电话号码的黑名单");
                duplicate++;
            } else {
                row.setStatus("VALID");
                valid++;
            }
            rowMapper.insert(row);
        }
        batch.setValidCount(valid);
        batch.setInvalidCount(invalid);
        batch.setDuplicateCount(duplicate);
        batchMapper.updateById(batch);
        return importResponse(batch, rows(batch.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public OutboundBlacklistImportResponse confirm(Long batchId) {
        OutboundBlacklistImportBatch batch = requireBatch(batchId);
        if ("IMPORTED".equals(batch.getStatus())) {
            return importResponse(batch, rows(batchId));
        }
        int locked = batchMapper.update(null, new LambdaUpdateWrapper<OutboundBlacklistImportBatch>()
            .eq(OutboundBlacklistImportBatch::getId, batchId)
            .eq(OutboundBlacklistImportBatch::getStatus, "PREVIEW")
            .set(OutboundBlacklistImportBatch::getStatus, "IMPORTING"));
        if (locked == 0) {
            throw new ServiceException("该黑名单导入批次正在处理，请勿重复确认");
        }
        int imported = 0;
        for (OutboundBlacklistImportRow row : rows(batchId)) {
            if (!"VALID".equals(row.getStatus())) {
                continue;
            }
            OutboundBlacklist blacklist = new OutboundBlacklist();
            blacklist.setScopeType(batch.getScopeType());
            blacklist.setTaskId(batch.getTaskId());
            blacklist.setOriginalPhone(row.getOriginalPhone());
            blacklist.setNormalizedPhone(row.getNormalizedPhone());
            blacklist.setReason(row.getReason());
            blacklist.setSource("EXCEL");
            blacklist.setEnabled(true);
            try {
                blacklistMapper.insert(blacklist);
                memberSyncService.blockMembers(blacklist);
                imported++;
            } catch (DuplicateKeyException exception) {
                row.setStatus("DUPLICATE_EXISTING");
                row.setErrorMessage("确认导入时发现该黑名单已存在");
                rowMapper.updateById(row);
            }
        }
        List<OutboundBlacklistImportRow> rows = rows(batchId);
        batch.setStatus("IMPORTED");
        batch.setImportedCount(imported);
        batch.setValidCount((int) rows.stream().filter(row -> "VALID".equals(row.getStatus())).count());
        batch.setInvalidCount((int) rows.stream().filter(row -> "INVALID".equals(row.getStatus())).count());
        batch.setDuplicateCount((int) rows.stream().filter(row -> row.getStatus().startsWith("DUPLICATE")).count());
        batchMapper.updateById(batch);
        return importResponse(batch, rows);
    }

    public void downloadErrors(Long batchId, HttpServletResponse response) {
        requireBatch(batchId);
        List<OutboundBlacklistImportErrorVo> errors = rows(batchId).stream()
            .filter(row -> !"VALID".equals(row.getStatus()))
            .map(row -> {
                OutboundBlacklistImportErrorVo vo = new OutboundBlacklistImportErrorVo();
                vo.setRowNumber(row.getRowNumber());
                vo.setOriginalPhone(row.getOriginalPhone());
                vo.setNormalizedPhone(row.getNormalizedPhone());
                vo.setReason(row.getReason());
                vo.setStatus(statusLabel(row.getStatus()));
                vo.setErrorMessage(row.getErrorMessage());
                return vo;
            }).toList();
        ExcelUtil.exportExcel(errors, "黑名单导入失败明细", OutboundBlacklistImportErrorVo.class, response);
    }

    private void apply(OutboundBlacklist blacklist, OutboundBlacklistRequest request) {
        validateScope(request.getScopeType(), request.getTaskId());
        if (!SOURCES.contains(request.getSource())) {
            throw new ServiceException("黑名单来源不支持");
        }
        String normalized = phoneNumberNormalizer.normalize(request.getPhoneNumber());
        if (!phoneNumberNormalizer.isValid(normalized)) {
            throw new ServiceException("电话号码格式无效");
        }
        if (request.getEffectiveAt() != null && request.getExpiresAt() != null
            && !request.getExpiresAt().isAfter(request.getEffectiveAt())) {
            throw new ServiceException("失效时间必须晚于生效时间");
        }
        blacklist.setScopeType(request.getScopeType());
        blacklist.setTaskId("TASK".equals(request.getScopeType()) ? request.getTaskId() : null);
        blacklist.setOriginalPhone(request.getPhoneNumber().trim());
        blacklist.setNormalizedPhone(normalized);
        blacklist.setReason(trim(request.getReason()));
        blacklist.setSource(request.getSource());
        blacklist.setEffectiveAt(request.getEffectiveAt());
        blacklist.setExpiresAt(request.getExpiresAt());
        blacklist.setEnabled(request.getEnabled());
    }

    private void validateScope(String scopeType, Long taskId) {
        if (!SCOPES.contains(scopeType)) {
            throw new ServiceException("黑名单范围只支持 GLOBAL 或 TASK");
        }
        if ("TASK".equals(scopeType) && (taskId == null || taskMapper.selectById(taskId) == null)) {
            throw new ServiceException("任务级黑名单必须选择当前租户内存在的外呼任务");
        }
    }

    private boolean exists(String scope, Long taskId, String phone, Long excludedId) {
        return blacklistMapper.exists(new LambdaQueryWrapper<OutboundBlacklist>()
            .eq(OutboundBlacklist::getScopeType, scope)
            .eq("TASK".equals(scope), OutboundBlacklist::getTaskId, taskId)
            .eq(OutboundBlacklist::getNormalizedPhone, phone)
            .ne(excludedId != null, OutboundBlacklist::getId, excludedId));
    }

    private OutboundBlacklist require(Long id) {
        OutboundBlacklist blacklist = blacklistMapper.selectById(id);
        if (blacklist == null) {
            throw new ServiceException("外呼黑名单不存在");
        }
        return blacklist;
    }

    private OutboundBlacklistImportBatch requireBatch(Long id) {
        OutboundBlacklistImportBatch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new ServiceException("黑名单导入批次不存在");
        }
        return batch;
    }

    private List<OutboundBlacklistImportRow> rows(Long batchId) {
        return rowMapper.selectList(new LambdaQueryWrapper<OutboundBlacklistImportRow>()
            .eq(OutboundBlacklistImportRow::getBatchId, batchId)
            .orderByAsc(OutboundBlacklistImportRow::getRowNumber));
    }

    private OutboundBlacklistResponse response(OutboundBlacklist blacklist) {
        OutboundBlacklistResponse response = new OutboundBlacklistResponse();
        response.setId(blacklist.getId());
        response.setScopeType(blacklist.getScopeType());
        response.setTaskId(blacklist.getTaskId());
        OutboundTask task = blacklist.getTaskId() == null ? null : taskMapper.selectById(blacklist.getTaskId());
        response.setTaskName(task == null ? null : task.getTaskName());
        response.setOriginalPhone(blacklist.getOriginalPhone());
        response.setNormalizedPhone(blacklist.getNormalizedPhone());
        response.setReason(blacklist.getReason());
        response.setSource(blacklist.getSource());
        response.setEffectiveAt(blacklist.getEffectiveAt());
        response.setExpiresAt(blacklist.getExpiresAt());
        response.setEnabled(blacklist.getEnabled());
        LocalDateTime now = LocalDateTime.now();
        response.setActive(Boolean.TRUE.equals(blacklist.getEnabled())
            && (blacklist.getEffectiveAt() == null || !blacklist.getEffectiveAt().isAfter(now))
            && (blacklist.getExpiresAt() == null || blacklist.getExpiresAt().isAfter(now)));
        response.setCreateTime(blacklist.getCreateTime());
        return response;
    }

    private OutboundBlacklistImportResponse importResponse(OutboundBlacklistImportBatch batch, List<OutboundBlacklistImportRow> rows) {
        OutboundBlacklistImportResponse response = new OutboundBlacklistImportResponse();
        response.setId(batch.getId());
        response.setScopeType(batch.getScopeType());
        response.setTaskId(batch.getTaskId());
        response.setFileName(batch.getFileName());
        response.setStatus(batch.getStatus());
        response.setTotalCount(batch.getTotalCount());
        response.setValidCount(batch.getValidCount());
        response.setInvalidCount(batch.getInvalidCount());
        response.setDuplicateCount(batch.getDuplicateCount());
        response.setImportedCount(batch.getImportedCount());
        response.setRows(rows.stream().map(row -> {
            OutboundBlacklistImportResponse.Row item = new OutboundBlacklistImportResponse.Row();
            item.setId(row.getId());
            item.setRowNumber(row.getRowNumber());
            item.setOriginalPhone(row.getOriginalPhone());
            item.setNormalizedPhone(row.getNormalizedPhone());
            item.setReason(row.getReason());
            item.setStatus(row.getStatus());
            item.setErrorMessage(row.getErrorMessage());
            return item;
        }).toList());
        return response;
    }

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "INVALID" -> "号码无效";
            case "DUPLICATE_FILE" -> "文件内重复";
            case "DUPLICATE_EXISTING" -> "黑名单已存在";
            default -> status;
        };
    }
}
