package com.morales.chemicallab.security;

import com.morales.chemicallab.agreement.AgreementService;
import com.morales.chemicallab.config.WebSocketConfig;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.security.limits.AccountTrafficLimits;
import com.morales.chemicallab.security.limits.TransportLimits;
import com.morales.chemicallab.service.InstitutionalWhiteboardAccess;
import com.morales.chemicallab.subscription.SubscriptionLedger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.UUID;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhiteboardOutboundInterceptorTest {
    private static final String DESTINATION = "/topic/institutions/1/classes/2/whiteboards/v1/3";

    @Test void hiddenJoinEventDoesNotBlockAuthorizedDrawsOnTheOrderedChannel() {
        var connections = mock(WhiteboardConnections.class);
        when(connections.delivery("student", "board", DESTINATION, null)).thenReturn(true);
        var outbound = new ExecutorSubscribableChannel();
        outbound.addInterceptor(new WhiteboardOutboundInterceptor(connections));
        var delivered = new ArrayList<>();
        outbound.subscribe(message -> delivered.add(message.getPayload()));
        OrderedMessageChannelDecorator.configureInterceptor(outbound, true);
        var ordered = new OrderedMessageChannelDecorator(outbound, LogFactory.getLog(getClass()));

        ordered.send(message("another student joined", 0L));
        ordered.send(message("draw revision 1", null));
        ordered.send(message("draw revision 2", null));

        assertThat(delivered).containsExactly("draw revision 1", "draw revision 2");
        verify(connections, org.mockito.Mockito.times(2)).delivery("student", "board", DESTINATION, null);
    }

    @Test void aQueuedParticipantNoticeCannotFollowASocketIdentifierToAnotherParticipant() throws Exception {
        var access = mock(InstitutionalWhiteboardAccess.class);
        when(access.installed()).thenReturn(true);
        var connections = new WhiteboardConnections(mock(AccountSessionService.class), mock(WhiteboardRealtimeAccess.class),
            new TransportLimits(), mock(AccountTrafficLimits.class), access, mock(AgreementService.class),
            mock(SubscriptionLedger.class));
        var transport = connections.decorate(mock(WebSocketHandler.class));
        var original = registerStudent(connections, transport, 10L);
        var tasks = new ArrayDeque<Runnable>();
        var outbound = new ExecutorSubscribableChannel(tasks::add);
        var interceptor = new WhiteboardOutboundInterceptor(connections);
        outbound.addInterceptor(interceptor);
        var delivered = new ArrayList<>();
        outbound.subscribe(message -> delivered.add(message.getPayload()));
        OrderedMessageChannelDecorator.configureInterceptor(outbound, true);
        new WebSocketConfig(null, connections, interceptor, new TransportLimits())
            .orderOutboundInterceptors(outbound).afterSingletonsInstantiated();
        var ordered = new OrderedMessageChannelDecorator(outbound, LogFactory.getLog(getClass()));

        ordered.send(message("permission for the original participant", 10L));
        assertThat(tasks).hasSize(1);
        transport.afterConnectionClosed(original, CloseStatus.NORMAL);
        var replacement = registerStudent(connections, transport, 11L);
        ordered.send(message("draw revision 1", null));
        ordered.send(message("draw revision 2", null));
        while (!tasks.isEmpty()) tasks.remove().run();

        assertThat(delivered).containsExactly("draw revision 1", "draw revision 2");
        transport.afterConnectionClosed(replacement, CloseStatus.NORMAL);
    }

    private WebSocketSession registerStudent(WhiteboardConnections connections, WebSocketHandler transport, long account)
            throws Exception {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("student");
        transport.afterConnectionEstablished(socket);
        connections.authenticated("student", new SessionPrincipal(account, "Student" + account, UUID.randomUUID(),
            0, Role.ESTUDIANTE, false), "1/2/3/student");
        connections.subscribe("student", "board", 3L);
        return socket;
    }

    private Message<String> message(String body, Long audience) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("student");
        headers.setSubscriptionId("board");
        headers.setDestination(DESTINATION);
        if (audience != null) headers.setHeader("boardParticipantAudience", audience);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(body, headers.getMessageHeaders());
    }
}
