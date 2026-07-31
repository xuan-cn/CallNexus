package org.dromara.resource.acl.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.dromara.resource.acl.domain.FreeSwitchAclEntry;

import java.util.ArrayList;
import java.util.List;

@Data
public class FreeSwitchAclSaveRequest {
    @NotNull
    private Long nodeId;
    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]{1,63}$")
    private String aclCode;
    @NotBlank
    private String aclName;
    @NotBlank
    private String purpose;
    @NotBlank
    @Pattern(regexp = "^(ALLOW|DENY)$")
    private String defaultAction;
    @Valid
    private List<FreeSwitchAclEntry> entries = new ArrayList<>();
    @NotNull
    private Boolean enabled;
    private Integer version;
}
