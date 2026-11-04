package org.dromara.customer.sms.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.sms.domain.SmsRequests.Variable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class SmsTemplateRendererTest {
    @Test void matchesTemplateVariablesExactly() {
        SmsTemplateRenderer.validate("Hello ${name}", List.of(new Variable("name", "MANUAL")), false);
        assertThrows(ServiceException.class, () -> SmsTemplateRenderer.validate("Hello ${name}", List.of(), false));
        assertThrows(ServiceException.class, () -> SmsTemplateRenderer.validate("Hello", List.of(new Variable("name", "MANUAL")), false));
    }
    @Test void rejectsDuplicateKeys() {
        var variables = List.of(new Variable("name", "MANUAL"), new Variable("name", "CUSTOMER_NAME"));
        assertThrows(ServiceException.class, () -> SmsTemplateRenderer.validate("Hello ${name}", variables, false));
    }
    @Test void tencentNeedsConsecutiveOrderedKeys() {
        SmsTemplateRenderer.validate("${1} ${2}", List.of(new Variable("1", "MANUAL"), new Variable("2", "MANUAL")), true);
        assertThrows(ServiceException.class, () -> SmsTemplateRenderer.validate("${2} ${1}", List.of(new Variable("2", "MANUAL"), new Variable("1", "MANUAL")), true));
    }
    @Test void autoVariablesCannotBeOverridden() {
        CustomerResponse customer = new CustomerResponse(); customer.setCustomerName("Customer");
        var variables = List.of(new Variable("name", "CUSTOMER_NAME"));
        assertEquals("Customer", SmsTemplateRenderer.resolve(variables, Map.of(), customer).get("name"));
        assertThrows(ServiceException.class, () -> SmsTemplateRenderer.resolve(variables, Map.of("name", "Other"), customer));
    }
    @Test void missingAndUnknownValuesAreRejected() {
        var variables = List.of(new Variable("code", "MANUAL"));
        assertThrows(ServiceException.class, () -> SmsTemplateRenderer.resolve(variables, Map.of(), null));
        assertThrows(ServiceException.class, () -> SmsTemplateRenderer.resolve(variables, Map.of("code", "42", "unknown", "x"), null));
        assertThrows(ServiceException.class, () -> SmsTemplateRenderer.resolve(variables, Map.of("code", "x".repeat(201)), null));
    }
    @Test void testModeCanSupplyAutoMappedValuesWithoutCustomer() {
        assertEquals("Test", SmsTemplateRenderer.resolve(List.of(new Variable("name", "CUSTOMER_NAME")), Map.of("name", "Test"), null).get("name"));
    }
    @Test void replacementTextIsNotInterpretedAsRegexOrAnotherTemplate() {
        assertEquals("Hello $1\\${other}", SmsTemplateRenderer.render("Hello ${name}", Map.of("name", "$1\\${other}")));
    }
}
