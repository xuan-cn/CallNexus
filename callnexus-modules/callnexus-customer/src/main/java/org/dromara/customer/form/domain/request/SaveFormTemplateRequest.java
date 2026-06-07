package org.dromara.customer.form.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormFieldType;

import java.util.ArrayList;
import java.util.List;

@Data
public class SaveFormTemplateRequest {
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$")
    private String templateCode;
    @NotBlank
    @Size(max = 64)
    private String templateName;
    @NotNull
    private FormBusinessType businessType;
    @NotNull
    private Boolean enabled;
    @Valid
    private List<FieldRequest> fields = new ArrayList<>();

    @Data
    public static class FieldRequest {
        @NotBlank
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,31}$")
        private String fieldCode;
        @NotBlank
        @Size(max = 64)
        private String fieldName;
        @NotNull
        private FormFieldType fieldType;
        @NotNull
        private Boolean required;
        private Integer sortOrder;
        @Min(12)
        @Max(24)
        private Integer layoutSpan;
        @Size(max = 500)
        private String defaultValue;
        @Size(max = 255)
        private String placeholder;
        @Size(max = 1000)
        private String validationRules;
        @Valid
        private List<OptionRequest> options = new ArrayList<>();
    }

    @Data
    public static class OptionRequest {
        @NotBlank
        @Size(max = 64)
        private String label;
        @NotBlank
        @Size(max = 64)
        private String value;
        private Integer sortOrder;
    }
}
