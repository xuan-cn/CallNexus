package org.dromara.resource.gateway;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.gateway.controller.FreeSwitchGatewayController;
import org.dromara.resource.gateway.mapper.FreeSwitchGatewayMapper;
import org.dromara.resource.gateway.service.FreeSwitchGatewayRuntimeQuery;
import org.dromara.resource.gateway.service.FreeSwitchGatewayStatusService;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class FreeSwitchGatewayStatusServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void limitsBatchSizeBeforeDatabaseAccess() {
        var mapper = mock(FreeSwitchGatewayMapper.class);
        ObjectProvider<FreeSwitchGatewayRuntimeQuery> provider = mock(ObjectProvider.class);
        var service = new FreeSwitchGatewayStatusService(mapper, provider);
        assertThrows(ServiceException.class, () -> service.query(List.of()));
        assertThrows(ServiceException.class, () -> service.query(Collections.nCopies(101, 1L)));
        assertThrows(ServiceException.class, () -> service.query(Collections.singletonList(null)));
        verifyNoInteractions(mapper, provider);
    }

    @Test
    void usesExistingListPermission() throws Exception {
        var method = FreeSwitchGatewayController.class.getMethod("runtimeStatus", List.class);
        assertArrayEquals(new String[]{"callcenter:freeswitch-gateway:list"}, method.getAnnotation(SaCheckPermission.class).value());
    }
}
