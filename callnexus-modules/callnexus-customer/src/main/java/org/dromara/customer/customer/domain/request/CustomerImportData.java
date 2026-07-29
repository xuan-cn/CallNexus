package org.dromara.customer.customer.domain.request;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CustomerImportData {
    private String customerName;
    private String primaryPhone;
    private List<Phone> additionalPhones = new ArrayList<>();

    @Data
    public static class Phone {
        private String phoneNumber;
        private String phoneLabel;
    }
}
