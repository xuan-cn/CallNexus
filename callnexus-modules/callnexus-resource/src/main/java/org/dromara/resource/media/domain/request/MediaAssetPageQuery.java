package org.dromara.resource.media.domain.request;

import lombok.Data;

@Data
public class MediaAssetPageQuery {
    private String assetName;
    private String category;
    private String sourceType;
    private Boolean enabled;
}
