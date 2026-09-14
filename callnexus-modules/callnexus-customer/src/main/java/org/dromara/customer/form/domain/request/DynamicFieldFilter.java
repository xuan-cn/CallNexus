package org.dromara.customer.form.domain.request;

import lombok.Data;

@Data
public class DynamicFieldFilter {
    private String fieldCode;
    private String operator;
    private Object value;
    private Object endValue;
}
