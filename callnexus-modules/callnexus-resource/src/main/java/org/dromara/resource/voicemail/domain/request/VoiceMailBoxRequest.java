package org.dromara.resource.voicemail.domain.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VoiceMailBoxRequest {
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$")
    private String boxCode;
    @NotBlank
    @Size(max = 128)
    private String boxName;
    @NotNull
    private Long promptMediaId;
    @NotNull
    @Min(10)
    @Max(600)
    private Integer maxSeconds;
    @NotNull
    @Min(0)
    @Max(1000)
    private Integer silenceThreshold;
    @NotNull
    @Min(1)
    @Max(20)
    private Integer silenceHits;
    @NotNull
    private Boolean enabled;
    @Size(max = 500)
    private String remark;
    private Integer version;
}
