package org.dromara.customer.form.domain.response;

import lombok.Data;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormFieldType;

import java.util.ArrayList;
import java.util.List;

@Data
public class FormTemplateResponse {
    private Long id;
    private String templateCode;
    private String templateName;
    private FormBusinessType businessType;
    private Boolean enabled;
    private Integer version;
    private List<FieldResponse> fields = new ArrayList<>();

    @Data
    public static class FieldResponse {
        private Long id;
        private String fieldCode;
        private String fieldName;
        private FormFieldType fieldType;
        private Boolean required;
        private Integer sortOrder;
        private Integer layoutSpan;
        private String defaultValue;
        private String placeholder;
        private String validationRules;
        private List<OptionResponse> options = new ArrayList<>();
    }

    @Data
    public static class OptionResponse {
        private Long id;
        private String label;
        private String value;
        private Integer sortOrder;
    }
}
