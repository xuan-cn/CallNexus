package org.dromara.outbound.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.outbound.domain.request.OutboundBlacklistRequest;
import org.dromara.outbound.domain.response.OutboundBlacklistImportResponse;
import org.dromara.outbound.domain.response.OutboundBlacklistResponse;
import org.dromara.outbound.service.OutboundBlacklistService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/outbound-blacklists")
@RequiredArgsConstructor
public class OutboundBlacklistController {
    private final OutboundBlacklistService service;

    @GetMapping
    @SaCheckPermission("callcenter:outbound-blacklist:list")
    public R<List<OutboundBlacklistResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-blacklist:query")
    public R<OutboundBlacklistResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:outbound-blacklist:create")
    public R<Long> create(@Valid @RequestBody OutboundBlacklistRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-blacklist:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody OutboundBlacklistRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:outbound-blacklist:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/enable")
    @SaCheckPermission("callcenter:outbound-blacklist:update")
    public R<Void> enable(@PathVariable Long id) {
        service.enable(id);
        return R.ok();
    }

    @PostMapping("/{id}/disable")
    @SaCheckPermission("callcenter:outbound-blacklist:update")
    public R<Void> disable(@PathVariable Long id) {
        service.disable(id);
        return R.ok();
    }

    @PostMapping("/import-template")
    @SaCheckPermission("callcenter:outbound-blacklist:import")
    public void importTemplate(HttpServletResponse response) {
        service.downloadTemplate(response);
    }

    @PostMapping(value = "/import-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission("callcenter:outbound-blacklist:import")
    public R<OutboundBlacklistImportResponse> preview(@RequestParam String scopeType,
                                                       @RequestParam(required = false) Long taskId,
                                                       @RequestPart("file") MultipartFile file) {
        return R.ok(service.preview(scopeType, taskId, file));
    }

    @PostMapping("/import-batches/{batchId}/confirm")
    @SaCheckPermission("callcenter:outbound-blacklist:import")
    public R<OutboundBlacklistImportResponse> confirm(@PathVariable Long batchId) {
        return R.ok(service.confirm(batchId));
    }

    @PostMapping("/import-batches/{batchId}/errors")
    @SaCheckPermission("callcenter:outbound-blacklist:import")
    public void errors(@PathVariable Long batchId, HttpServletResponse response) {
        service.downloadErrors(batchId, response);
    }
}
