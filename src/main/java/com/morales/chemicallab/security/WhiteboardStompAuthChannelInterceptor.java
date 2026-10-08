package com.morales.chemicallab.security;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/** T02 validates new CONNECT only. Destination authorization and existing connections belong to T03. */
@Component
@RequiredArgsConstructor
public class WhiteboardStompAuthChannelInterceptor implements ChannelInterceptor {
    private final AccountSessionService sessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) return message;
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) throw new BadCredentialsException("Bearer requerido.");
        var authentication = sessions.authenticate(header.substring(7).trim());
        if (((SessionPrincipal) authentication.getPrincipal()).temporaryPassword()) {
            throw new AccessDeniedException("PASSWORD_CHANGE_REQUIRED");
        }
        accessor.setUser(authentication);
        return message;
    }
}
