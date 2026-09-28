package org.dromara.resource.outboundline.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OutboundLinePolicyRequest {
    @NotNull
    private Long nodeId;
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$")
    private String policyCode;
    @NotBlank
    @Size(max = 128)
    private String policyName;
    @NotBlank
    @Pattern(regexp = "^(FIXED|ROUND_ROBIN|WEIGHT)$")
    private String policyType;
    @Size(max = 16)
    @Pattern(regexp = "^$|^0\\d{2,3}$", message = "本地区号格式不正确")
    private String localAreaCode;
    @NotNull
    private Boolean addLocalAreaCode = false;
    @NotNull
    private Boolean addMissingAreaCodeZero = false;
    @NotNull
    private Boolean stripChinaCountryCode = false;
    @Size(max = 16)
    @Pattern(regexp = "^$|^\\d{1,16}$", message = "出局前缀只能包含数字")
    private String outboundPrefix;
    @NotNull
    private Boolean defaultPolicy = false;
    @NotNull
    private Boolean enabled = true;
    @Size(max = 500)
    private String remark;
    private Integer version;
    @Valid
    @NotEmpty
    private List<OutboundLinePolicyItemRequest> items = new ArrayList<>();
}
