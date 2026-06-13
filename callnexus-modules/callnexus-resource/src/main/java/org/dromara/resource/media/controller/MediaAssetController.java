package org.dromara.resource.media.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.media.domain.MediaAssetCategory;
import org.dromara.resource.media.domain.request.MediaAssetPageQuery;
import org.dromara.resource.media.domain.request.UpdateMediaAssetRequest;
import org.dromara.resource.media.domain.response.MediaAssetResponse;
import org.dromara.resource.media.service.MediaAssetApplicationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media-assets")
@RequiredArgsConstructor
public class MediaAssetController {
    private final MediaAssetApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:media-asset:list")
    public TableDataInfo<MediaAssetResponse> page(MediaAssetPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:media-asset:query")
    public R<MediaAssetResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission("callcenter:media-asset:create")
    public R<Long> upload(@RequestParam String assetName,
                          @RequestParam MediaAssetCategory category,
                          @RequestParam(required = false) String languageCode,
                          @RequestParam(required = false) String remark,
                          @RequestParam(required = false) Long durationMs,
                          @RequestPart("file") MultipartFile file) {
        return R.ok(applicationService.upload(assetName, category, languageCode, remark, durationMs, file));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:media-asset:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateMediaAssetRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:media-asset:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }
}
