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
