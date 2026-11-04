package org.dromara.resource.sip;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.sip.controller.SipAccountController;
import org.dromara.resource.sip.domain.SipAccount;
import org.dromara.resource.sip.mapper.SipAccountMapper;
import org.dromara.resource.sip.service.SipAccountApplicationService;
import org.dromara.resource.sip.service.impl.SipAccountApplicationServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class SipAccountPasswordTest {
    @Test
    void readsStoredPasswordAndRejectsMissingAccount() {
        SipAccountMapper mapper = mock(SipAccountMapper.class);
        SipAccount account = new SipAccount();
        account.setAuthPassword("test-password-only");
        when(mapper.selectById(1L)).thenReturn(account);
        SipAccountApplicationServiceImpl service = new SipAccountApplicationServiceImpl(mapper, null, null, null);

        assertEquals("test-password-only", service.getPassword(1L));
        assertThrows(ServiceException.class, () -> service.getPassword(2L));
        verify(mapper, never()).updateById(any(SipAccount.class));
    }

    @Test
    void passwordEndpointRequiresUpdatePermissionAndDisablesCaching() throws Exception {
        SipAccountApplicationService service = mock(SipAccountApplicationService.class);
        when(service.getPassword(1L)).thenReturn("test-password-only");
        HttpServletResponse response = mock(HttpServletResponse.class);
        var controller = new SipAccountController(service, null);
        var result = controller.password(1L, response);

        assertEquals("test-password-only", result.getData().password());
        assertFalse(result.getData().toString().contains("test-password-only"));
        verify(response).setHeader("Cache-Control", "no-store");
        var method = SipAccountController.class.getMethod("password", Long.class, HttpServletResponse.class);
        assertArrayEquals(new String[]{"callcenter:sip-account:update"}, method.getAnnotation(SaCheckPermission.class).value());
    }
}
