package org.dromara.resource.media.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateMediaAssetRequest {
    @NotBlank
    @Size(max = 128)
    private String assetName;
    @NotBlank
    @Pattern(regexp = "^(IVR_PROMPT|QUEUE_WAIT_MUSIC|RINGBACK_TONE|USER_MUSIC|CALL_RECORDING)$")
    private String category;
    @Size(max = 16)
    private String languageCode;
    @Size(max = 500)
    private String remark;
    @NotNull
    private Boolean enabled;
    @NotNull
    private Integer version;
}
