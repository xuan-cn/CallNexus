package org.dromara.resource.media.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.media.domain.MediaAssetCategory;
import org.dromara.resource.media.domain.request.MediaAssetPageQuery;
import org.dromara.resource.media.domain.request.UpdateMediaAssetRequest;
import org.dromara.resource.media.domain.response.MediaAssetResponse;
import org.springframework.web.multipart.MultipartFile;

public interface MediaAssetApplicationService {
    TableDataInfo<MediaAssetResponse> page(MediaAssetPageQuery query, PageQuery pageQuery);

    MediaAssetResponse get(Long id);

    Long upload(String assetName, MediaAssetCategory category, String languageCode, String remark, Long durationMs, MultipartFile file);

    MediaAssetResponse storeRecording(String businessCallId, Long durationMs, MultipartFile file);

    void update(Long id, UpdateMediaAssetRequest request);

    void delete(Long id);
}
