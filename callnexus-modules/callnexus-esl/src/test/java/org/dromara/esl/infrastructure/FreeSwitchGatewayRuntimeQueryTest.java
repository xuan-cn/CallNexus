package org.dromara.esl.infrastructure;

import org.dromara.call.domain.EslEndpoint;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class FreeSwitchGatewayRuntimeQueryTest {
    private static final String XML = """
        <?xml version="1.0" encoding="ISO-8859-1"?>
        <gateways><gateway><name>carrier</name><profile>external</profile>
        <state>REGED</state><status>UP</status><pingfreq>30</pingfreq><retry/></gateway></gateways>
        """;

    @Test
    void parsesFreeSwitchGatewaySummary() {
        var status = GatewayStatusXml.gateways(XML).get("carrier");
        assertEquals("REGED", status.state());
        assertEquals("UP", status.status());
        assertEquals(30, status.pingSeconds());
        assertTrue(GatewayStatusXml.gateways("<gateways/>").isEmpty());
    }

    @Test
    void rejectsInvalidReplyAndExternalEntities() {
        assertThrows(IllegalArgumentException.class, () -> GatewayStatusXml.gateways("-ERR invalid command"));
        assertThrows(IllegalArgumentException.class, () -> GatewayStatusXml.gateways("<profiles/>"));
        assertThrows(IllegalArgumentException.class, () -> GatewayStatusXml.gateways(
            "<!DOCTYPE gateways [<!ENTITY x SYSTEM 'file:///not-readable'>]><gateways>&x;</gateways>"));
    }

    @Test
    void registeredStateDoesNotHideProbeFailure() {
        var gateway = gateway(1L, 1L, "OUTBOUND_REGISTER");
        assertEquals("REGISTERED", classify(gateway, "REGED", "UP", 30));
        assertEquals("UNREACHABLE", classify(gateway, "REGED", "DOWN", 30));
        assertEquals("REGISTRATION_FAILED", classify(gateway, "FAIL_WAIT", "UP", 30));
        assertEquals("REGISTERING", classify(gateway, "TRYING", "UP", 30));
        assertEquals("UNREGISTERED", classify(gateway, "UNREGED", "UP", 0));
        assertEquals("QUERY_FAILED", classify(gateway, "REGED", "UNKNOWN", 30));
    }

    @Test
    void noRegistrationAndNoProbeIsNotHealthy() {
        var gateway = gateway(1L, 1L, "IP_TRUNK");
        assertEquals("NO_PROBE", classify(gateway, "NOREG", "UP", 0));
        assertEquals("UP", classify(gateway, "NOREG", "UP", 30));
        assertEquals("UNREACHABLE", classify(gateway, "NOREG", "DOWN", 30));
        assertEquals("QUERY_FAILED", classify(gateway, "REGED", "UP", 30));
        assertEquals("NOT_LOADED", FreeSwitchGatewayRuntimeQueryImpl.classify(gateway, null).status());
    }

    @Test
    void deviceRegistrationsExcludeExpiredAndOtherDomains() {
        var result = GatewayStatusXml.registrations("""
            <profile><registrations>
            <registration><user>1001@example.test</user><status>Registered(UDP) expsecs(120)</status></registration>
            <registration><user>1002@example.test</user><status>Registered(UDP) expsecs(0)</status></registration>
            <registration><user>1003@example.test</user><status>Registered(UDP) expsecs(-1)</status></registration>
            <registration><user>1004@other.test</user><status>Registered(UDP) expsecs(120)</status></registration>
            </registrations></profile>
            """, "example.test");
        assertEquals(java.util.Set.of("1001"), result);
        assertThrows(IllegalArgumentException.class, () -> GatewayStatusXml.registrations("Invalid Profile!", "example.test"));
    }

    @Test
    void groupsQueriesAndIsolatesUnreachableNodes() {
        var nodes = mock(FreeSwitchNodeQueryService.class);
        var commands = mock(FreeSwitchEslCommandGateway.class);
        var node = new FreeSwitchNodeConnectionResponse();
        node.setEslHost("127.0.0.1");
        node.setEslPort(8021);
        node.setEslPassword("test-only");
        node.setSipDomain("example.test");
        when(nodes.getEnabledConnection(1L)).thenReturn(node);
        when(nodes.getEnabledConnection(2L)).thenThrow(new IllegalStateException("unavailable"));
        var endpoint = new EslEndpoint("127.0.0.1", 8021, "test-only", "example.test");
        when(commands.executeApiCommandForResult(endpoint, "api sofia xmlstatus gateway")).thenReturn(XML);
        var device = gateway(4L, 1L, "DEVICE_REGISTER");
        device.setRegisteredIdentity("1001");
        device.setSipProfile("internal");
        when(commands.executeApiCommandForResult(endpoint, "api sofia xmlstatus profile internal reg")).thenReturn("""
            <profile><registrations><registration><user>1001@example.test</user>
            <status>Registered(UDP) expsecs(120)</status></registration></registrations></profile>
            """);
        var result = new FreeSwitchGatewayRuntimeQueryImpl(nodes, commands).query(List.of(
            gateway(1L, 1L, "OUTBOUND_REGISTER"), gateway(2L, 1L, "OUTBOUND_REGISTER"),
            gateway(3L, 2L, "OUTBOUND_REGISTER"), device));
        assertEquals(3, result.stream().filter(item -> "REGISTERED".equals(item.status())).count());
        assertEquals(1, result.stream().filter(item -> "NODE_UNAVAILABLE".equals(item.status())).count());
        verify(commands, times(1)).executeApiCommandForResult(endpoint, "api sofia xmlstatus gateway");
        verify(commands, times(1)).executeApiCommandForResult(endpoint, "api sofia xmlstatus profile internal reg");
    }

    @Test
    void disabledGatewayNeverConnects() {
        var nodes = mock(FreeSwitchNodeQueryService.class);
        var commands = mock(FreeSwitchEslCommandGateway.class);
        var gateway = gateway(1L, 1L, "IP_TRUNK");
        gateway.setEnabled(false);
        var result = new FreeSwitchGatewayRuntimeQueryImpl(nodes, commands).query(List.of(gateway));
        assertEquals("DISABLED", result.get(0).status());
        verifyNoInteractions(nodes, commands);
    }

    @Test
    void connectionFailureIsNotReportedAsUnregistered() {
        var nodes = mock(FreeSwitchNodeQueryService.class);
        var commands = mock(FreeSwitchEslCommandGateway.class);
        var node = new FreeSwitchNodeConnectionResponse();
        node.setEslHost("127.0.0.1");
        node.setEslPort(8021);
        node.setEslPassword("test-only");
        node.setSipDomain("example.test");
        when(nodes.getEnabledConnection(1L)).thenReturn(node);
        when(commands.executeApiCommandForResult(any(), anyString())).thenThrow(new IllegalStateException("timeout"));
        var result = new FreeSwitchGatewayRuntimeQueryImpl(nodes, commands).query(List.of(gateway(1L, 1L, "OUTBOUND_REGISTER")));
        assertEquals("NODE_UNAVAILABLE", result.get(0).status());
    }

    @Test
    void invalidProfileNeverBecomesCommand() {
        var nodes = mock(FreeSwitchNodeQueryService.class);
        var commands = mock(FreeSwitchEslCommandGateway.class);
        var node = new FreeSwitchNodeConnectionResponse();
        node.setEslHost("127.0.0.1");
        node.setEslPort(8021);
        node.setEslPassword("test-only");
        node.setSipDomain("example.test");
        when(nodes.getEnabledConnection(1L)).thenReturn(node);
        var device = gateway(1L, 1L, "DEVICE_REGISTER");
        device.setSipProfile("internal\napi reloadxml");
        var result = new FreeSwitchGatewayRuntimeQueryImpl(nodes, commands).query(List.of(device));
        assertEquals("QUERY_FAILED", result.get(0).status());
        verifyNoInteractions(commands);
    }

    private String classify(FreeSwitchGateway gateway, String state, String probe, int ping) {
        return FreeSwitchGatewayRuntimeQueryImpl.classify(gateway, new GatewayStatusXml.Gateway(state, probe, ping, "")).status();
    }

    private FreeSwitchGateway gateway(Long id, Long nodeId, String mode) {
        var gateway = new FreeSwitchGateway();
        gateway.setId(id);
        gateway.setNodeId(nodeId);
        gateway.setGatewayCode("carrier");
        gateway.setAccessMode(mode);
        gateway.setEnabled(true);
        return gateway;
    }
}
