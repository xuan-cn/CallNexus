package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.resource.media.domain.MediaAssetCategory;

@Data
public class TtsMediaGenerateRequest {

    @NotBlank
    @Size(max = 128)
    private String assetName;

    @NotNull
    private MediaAssetCategory category;

    @Size(max = 32)
    private String languageCode;

    @NotBlank
    @Size(max = 1000)
    private String text;

    @Size(max = 500)
    private String remark;
}
