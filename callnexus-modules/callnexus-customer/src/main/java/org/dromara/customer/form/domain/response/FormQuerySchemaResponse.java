package org.dromara.customer.form.domain.response;

import lombok.Data;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormFieldType;
import org.dromara.customer.form.domain.FormQueryMode;

import java.util.ArrayList;
import java.util.List;

@Data
public class FormQuerySchemaResponse {
    private Long templateId;
    private String templateName;
    private FormBusinessType businessType;
    private List<Field> fields = new ArrayList<>();

    @Data
    public static class Field {
        private String fieldCode;
        private String fieldName;
        private FormFieldType fieldType;
        private FormQueryMode queryMode;
        private Integer queryOrder;
        private String placeholder;
        private String fieldRemark;
        private List<Option> options = new ArrayList<>();
    }

    @Data
    public static class Option {
        private String label;
        private String value;
    }
}
