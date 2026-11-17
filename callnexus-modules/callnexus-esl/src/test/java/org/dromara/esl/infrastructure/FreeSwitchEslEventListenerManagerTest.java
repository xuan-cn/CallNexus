package org.dromara.esl.infrastructure;

import org.dromara.esl.dispatcher.EslEventDispatcher;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class FreeSwitchEslEventListenerManagerTest {
    @Test
    void replacesEditedEndpointAndClosesRemovedNode() throws Exception {
        try (ServerSocket oldEndpoint = endpoint(); ServerSocket newEndpoint = endpoint()) {
            var query = mock(FreeSwitchNodeQueryService.class);
            var oldConfig = node(oldEndpoint.getLocalPort(), "old-token");
            when(query.listEnabledConnections()).thenReturn(List.of(oldConfig));
            var manager = new FreeSwitchEslEventListenerManager(query, mock(EslEventDispatcher.class),
                mock(FreeSwitchRuntimeReconciler.class));
            try {
                manager.start();
                try (Socket oldConnection = oldEndpoint.accept()) {
                    oldConnection.setSoTimeout(200);
                    var sameConfig = node(oldEndpoint.getLocalPort(), "old-token");
                    sameConfig.setSipDomain("not-an-esl-setting");
                    manager.reconcileListeners(List.of(sameConfig));
                    assertThatThrownBy(() -> oldConnection.getInputStream().read()).isInstanceOf(SocketTimeoutException.class);

                    var newConfig = node(newEndpoint.getLocalPort(), "old-token");
                    manager.reconcileListeners(List.of(newConfig));
                    assertClosed(oldConnection);
                    try (Socket newConnection = newEndpoint.accept()) {
                        manager.reconcileListeners(List.of(node(newEndpoint.getLocalPort(), "new-token")));
                        assertClosed(newConnection);
                        try (Socket updatedCredentials = newEndpoint.accept()) {
                            manager.reconcileListeners(List.of());
                            assertClosed(updatedCredentials);
                        }
                    }
                }
            } finally {
                manager.stop();
            }
        }
    }

    private static ServerSocket endpoint() throws Exception {
        var socket = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        socket.setSoTimeout(4000);
        return socket;
    }

    private static void assertClosed(Socket socket) throws Exception {
        socket.setSoTimeout(4000);
        assertThat(socket.getInputStream().read()).isEqualTo(-1);
    }

    private static FreeSwitchNodeConnectionResponse node(int port, String password) {
        var node = new FreeSwitchNodeConnectionResponse();
        node.setNodeId(1L);
        node.setEslHost("127.0.0.1");
        node.setEslPort(port);
        node.setEslPassword(password);
        return node;
    }
}
