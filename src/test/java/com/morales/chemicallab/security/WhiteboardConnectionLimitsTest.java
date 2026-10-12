package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.security.limits.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class WhiteboardConnectionLimitsTest {
    @Test void instanceAccountSubscriptionAndTargetEdgesReleaseOnClose() throws Exception {
        var limits = new TransportLimits(); limits.setConnections(4); limits.setAccountConnections(3); limits.setAccountSubscriptions(3); limits.setConnectionTargets(1);
        var registry = new WhiteboardConnections(mock(AccountSessionService.class), mock(WhiteboardRealtimeAccess.class), limits, new AccountTrafficLimits(limits));
        var handler = registry.decorate(mock(WebSocketHandler.class));
        var principal = new SessionPrincipal(1L, "fictitious", UUID.randomUUID(), 0, Role.ESTUDIANTE, false);
        try {
            for (int i = 1; i <= 4; i++) { var socket = mock(WebSocketSession.class); when(socket.getId()).thenReturn("s" + i); handler.afterConnectionEstablished(socket); }
            var excess = mock(WebSocketSession.class); handler.afterConnectionEstablished(excess);
            verify(excess).close(new CloseStatus(4429, "CONNECTION_LIMIT")); assertThat(registry.size()).isEqualTo(4);
            registry.authenticated("s1", principal); registry.authenticated("s2", principal); registry.authenticated("s3", principal);
            assertThatThrownBy(() -> registry.authenticated("s4", principal)).isInstanceOf(InputRejectedException.class);
            registry.observe("s1", 1); assertThatThrownBy(() -> registry.observe("s1", 2)).isInstanceOf(InputRejectedException.class);
            registry.subscribe("s1", "b", 1); registry.subscribe("s2", "b", 1); registry.subscribe("s3", "b", 1);
            assertThatThrownBy(() -> registry.subscribe("s3", "errors", 0)).isInstanceOf(InputRejectedException.class);
            registry.unsubscribe("s2", "b"); registry.subscribe("s3", "errors", 0);
            registry.unsubscribe("s1", "b"); registry.subscribe("s1", "b", 1);
            assertThatThrownBy(() -> registry.subscribe("s1", "repeat", 1)).isInstanceOf(InputRejectedException.class);
            registry.remove("s1"); registry.authenticated("s4", principal); registry.subscribe("s4", "b", 1);
        } finally { registry.stop(); }
        assertThat(registry.size()).isZero();
    }
}
