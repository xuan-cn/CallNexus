package org.dromara.license;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.license.LicenseManagementService.Status;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/license")
@RequiredArgsConstructor
public class LicenseRequestController {

    private final LicenseRequestService service;
    private final LicenseManagementService management;

    @GetMapping("/status")
    public ResponseEntity<R<Status>> status() {
        if (!LoginHelper.isSuperAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(management.status()));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<Status>> importLicense(@RequestParam("file") MultipartFile file) {
        if (!LoginHelper.isSuperAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (file.isEmpty() || file.getSize() > LicenseVerifier.MAX_BYTES) {
            throw new ServiceException("授权文件必须为 1 至 32768 字节");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(management.importLicense(file.getBytes(), LoginHelper.getUserId())));
        } catch (IOException e) {
            throw new ServiceException("无法读取授权文件");
        }
    }

    @PostMapping("/request/export")
    public ResponseEntity<byte[]> export() {
        if (!LoginHelper.isSuperAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("callnexus-license-request.json").build().toString())
            .body(service.export());
    }
}
