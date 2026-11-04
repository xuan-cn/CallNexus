package org.dromara.customer.sms.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.customer.sms.controller.BusinessSmsController;
import org.dromara.customer.sms.domain.SmsChannel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class BusinessSmsPermissionTest {
    @Test void customerActionsDoNotRequireAdministrativeSmsPermissions() {
        for (var method : BusinessSmsController.class.getDeclaredMethods()) {
            if (java.util.Set.of("context", "preview", "send", "customerRecords").contains(method.getName())) {
                assertArrayEquals(new String[]{"callcenter:customer:sms"}, method.getAnnotation(SaCheckPermission.class).value());
            }
        }
    }
    @Test void everyEndpointRequiresExplicitPermission() {
        for (var method : BusinessSmsController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertNotNull(method.getAnnotation(SaCheckPermission.class), method.getName());
        }
    }
    @Test void credentialsAreEncryptedAtRestAndExcludedFromResponseAndRequestLogs() throws Exception {
        assertNotNull(SmsChannel.class.getDeclaredField("accessKeyId").getAnnotation(EncryptField.class));
        assertNotNull(SmsChannel.class.getDeclaredField("accessKeySecret").getAnnotation(EncryptField.class));
        assertTrue(Arrays.asList(SystemConstants.EXCLUDE_PROPERTIES).containsAll(java.util.List.of("accessKeyId", "accessKeySecret")));
        for (var field : BusinessSmsService.ChannelView.class.getRecordComponents()) {
            assertFalse(java.util.Set.of("accessKeyId", "accessKeySecret").contains(field.getName()));
        }
    }
}
