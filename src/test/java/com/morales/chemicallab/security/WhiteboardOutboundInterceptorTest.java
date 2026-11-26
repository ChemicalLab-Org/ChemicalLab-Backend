package com.morales.chemicallab.security;

import java.util.ArrayList;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WhiteboardOutboundInterceptorTest {
    private static final String DESTINATION = "/topic/institutions/1/classes/2/whiteboards/v1/3";

    @Test void hiddenJoinEventDoesNotBlockAuthorizedDrawsOnTheOrderedChannel() {
        var connections = mock(WhiteboardConnections.class);
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
        verify(connections, org.mockito.Mockito.times(2)).delivery("student", "board", DESTINATION);
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
