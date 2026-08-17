package org.dromara.customer.customer.domain.request;

import lombok.Data;

@Data
public class CustomerImportRequest {
    private String duplicateStrategy = "SKIP";
    private String defaultCustomerType;
    private String defaultSourceChannel;
    private String defaultTags;
    private String defaultRemark;
    /**
     * Customer dynamic form template id. When set, mapped form fields will be saved
     * into the same dynamic form submission table used by customer details.
     */
    private Long formTemplateId;
    /**
     * JSON object: Excel header -> system field.
     * Example: {"手机号":"phone","姓名":"name","客户阶段":"customerType","预算":"form:budget"}
     */
    private String fieldMappingJson;
}
