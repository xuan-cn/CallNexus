package org.dromara.agent.service.impl;

import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.request.BindAgentExtensionRequest;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentApplicationServiceImplTest {
    private AgentMapper agentMapper;
    private AgentExtensionMapper extensionMapper;
    private SipAccountQueryService sipAccountQueryService;
    private AgentApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        agentMapper = mock(AgentMapper.class);
        extensionMapper = mock(AgentExtensionMapper.class);
        sipAccountQueryService = mock(SipAccountQueryService.class);
        service = new AgentApplicationServiceImpl(agentMapper, extensionMapper, sipAccountQueryService);
    }

    @Test
    void bindExtensionRejectsDisabledSipAccount() {
        BindAgentExtensionRequest request = new BindAgentExtensionRequest();
        request.setSipAccountId(1001L);
        when(agentMapper.selectById(10L)).thenReturn(new Agent());
        when(sipAccountQueryService.existsEnabled(1001L)).thenReturn(false);

        assertThrows(ServiceException.class, () -> service.bindExtension(10L, request));

        verify(extensionMapper, never()).insert(any(AgentExtension.class));
    }

    @Test
    void bindExtensionRejectsOccupiedSipAccount() {
        BindAgentExtensionRequest request = new BindAgentExtensionRequest();
        request.setSipAccountId(1001L);
        when(agentMapper.selectById(10L)).thenReturn(new Agent());
        when(sipAccountQueryService.existsEnabled(1001L)).thenReturn(true);
        when(extensionMapper.exists(any())).thenReturn(true);

        assertThrows(ServiceException.class, () -> service.bindExtension(10L, request));

        verify(extensionMapper, never()).insert(any(AgentExtension.class));
    }
}
