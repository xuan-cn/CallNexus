package org.dromara.resource.media.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.resource.media.domain.request.PublishMediaRequest;
import org.dromara.resource.media.domain.response.MediaSyncResponse;
import org.dromara.resource.media.domain.response.MediaVersionResponse;
import org.dromara.resource.media.service.MediaPublicationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/v1/media-assets/{mediaId}")
@RequiredArgsConstructor
public class MediaPublicationController {
    private final MediaPublicationService service;
    @GetMapping("/versions") @SaCheckPermission("callcenter:media-asset:query")
    public R<List<MediaVersionResponse>> versions(@PathVariable Long mediaId) { return R.ok(service.versions(mediaId)); }
    @GetMapping("/publication-groups") @SaCheckPermission("callcenter:media-asset:query")
    public R<List<Long>> publicationGroups(@PathVariable Long mediaId) { return R.ok(service.publishedGroupIds(mediaId)); }
    @PostMapping(value = "/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @SaCheckPermission("callcenter:media-asset:create")
    public R<Long> uploadVersion(@PathVariable Long mediaId, @RequestParam(required = false) Long durationMs,
                                 @RequestPart("file") MultipartFile file) { return R.ok(service.uploadVersion(mediaId, durationMs, file)); }
    @PostMapping("/publish") @SaCheckPermission("callcenter:media-asset:publish")
    public R<Void> publish(@PathVariable Long mediaId, @Valid @RequestBody PublishMediaRequest request) { service.publish(mediaId, request.getNodeGroupIds()); return R.ok(); }
    @PostMapping("/unpublish") @SaCheckPermission("callcenter:media-asset:publish")
    public R<Void> unpublish(@PathVariable Long mediaId) { service.unpublish(mediaId); return R.ok(); }
    @GetMapping("/syncs") @SaCheckPermission("callcenter:media-asset:sync")
    public R<List<MediaSyncResponse>> syncs(@PathVariable Long mediaId) { return R.ok(service.syncs(mediaId)); }
    @PostMapping("/syncs/retry") @SaCheckPermission("callcenter:media-asset:sync")
    public R<Void> retry(@PathVariable Long mediaId) { service.retryFailed(mediaId); return R.ok(); }
}
