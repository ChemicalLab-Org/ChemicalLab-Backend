package com.morales.chemicallab.security;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

/** Validate on the actual delivery thread, after queuing and separately for every recipient. */
@Component
@RequiredArgsConstructor
public class WhiteboardOutboundInterceptor implements ExecutorChannelInterceptor {
    private final WhiteboardConnections connections;

    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE
            || !(message.getHeaders().get("boardParticipantAudience") instanceof Long account)) return message;
        String id = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        try {
            // A synchronous discard lets Spring advance the ordered queue for this subscriber.
            return connections.includesParticipantEvent(id, account) ? message : null;
        } catch (Exception ex) {
            connections.reject(id, ex);
            return null;
        }
    }

    @Override public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) return message;
        String id = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        try {
            connections.delivery(id, SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders()),
                    SimpMessageHeaderAccessor.getDestination(message.getHeaders()));
            return message;
        } catch (Exception ex) {
            connections.reject(id, ex);
            return null;
        }
    }
}
