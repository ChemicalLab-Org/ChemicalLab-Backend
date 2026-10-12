package com.morales.chemicallab.security;

import com.morales.chemicallab.security.limits.AccountTrafficLimits;
import com.morales.chemicallab.security.limits.InputRejectedException;
import com.morales.chemicallab.security.limits.JsonInputGuard;
import com.morales.chemicallab.security.limits.TransportLimits;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/** Only explicitly authorized commands, exact destinations and authenticated connections pass. */
@Component
@RequiredArgsConstructor
public class WhiteboardStompAuthChannelInterceptor implements ChannelInterceptor {
    private final AccountSessionService sessions;
    private final WhiteboardConnections connections;
    private final AccountTrafficLimits traffic;
    private final JsonInputGuard guard;
    private final TransportLimits limits;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.messaging.simp.SimpMessagingTemplate> messaging;
    private static final Pattern TOPIC = Pattern.compile("/topic/whiteboards/([1-9][0-9]*)");
    private static final Pattern SEND = Pattern.compile("/app/whiteboards/([1-9][0-9]*)/(draw|presence)");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) throw new AccessDeniedException("PROTOCOL_REJECTED");
        String id = accessor.getSessionId();
        try {
            StompCommand command = accessor.getCommand();
            if (command == StompCommand.DISCONNECT) return message; // also synthetic cleanup
            if (command == StompCommand.CONNECT) {
                String header = accessor.getFirstNativeHeader("Authorization");
                accessor.removeNativeHeader("Authorization");
                if (header == null || !header.startsWith("Bearer ")) throw new BadCredentialsException("SESSION_INVALID");
                var authentication = sessions.authenticate(header.substring(7).trim());
                var principal = (SessionPrincipal) authentication.getPrincipal();
                if (principal.temporaryPassword()) throw new AccessDeniedException("PASSWORD_CHANGE_REQUIRED");
                connections.authenticated(id, principal);
                accessor.setUser(authentication);
                accessor.removeNativeHeader("Authorization");
                return message;
            }
            var principal = connections.principal(id);
            // Includes heartbeat and rejected operations: flooding rejections cannot flood the outbound queue.
            traffic.take(principal.userId(), "frame");
            connections.validate(id);
            if (accessor.getMessageType() == SimpMessageType.HEARTBEAT) return message;
            String destination = accessor.getDestination();
            if (command == StompCommand.SUBSCRIBE) {
                traffic.take(principal.userId(), "control");
                if ("/user/queue/whiteboard-errors".equals(destination)) {
                    connections.subscribe(id, accessor.getSubscriptionId(), 0); return message;
                }
                long board = board(TOPIC, destination);
                connections.observe(id, board);
                connections.subscribe(id, accessor.getSubscriptionId(), board);
            } else if (command == StompCommand.SEND) {
                connections.observe(id, board(SEND, destination));
                try {
                    traffic.take(principal.userId(), destination.endsWith("/draw") ? "draw" : "presence");
                    if (!(message.getPayload() instanceof byte[] bytes)) throw InputRejectedException.invalid();
                    if (bytes.length > limits.getStompBytes()) throw InputRejectedException.size();
                    if (destination.endsWith("/draw")) guard.check(bytes, false, false);
                    else if (bytes.length != 0) throw InputRejectedException.invalid();
                } catch (InputRejectedException ex) {
                    var headers = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(org.springframework.messaging.simp.SimpMessageType.MESSAGE);
                    headers.setSessionId(id); headers.setLeaveMutable(true);
                    String eventId = accessor.getFirstNativeHeader("client-event-id");
                    if (eventId == null || eventId.length() > 100) eventId = "";
                    messaging.getObject().convertAndSendToUser(principal.getName(), "/queue/whiteboard-errors",
                            java.util.Map.of("code", ex.code(), "error", ex.getMessage(), "resync", true, "accepted", false, "clientEventId", eventId), headers.getMessageHeaders());
                    return null;
                }
            } else if (command == StompCommand.UNSUBSCRIBE) {
                traffic.take(principal.userId(), "control");
                connections.unsubscribe(id, accessor.getSubscriptionId());
            } else throw new AccessDeniedException("PROTOCOL_REJECTED");
            return message;
        } catch (Exception ex) {
            connections.reject(id, ex);
            // The transport is already closed. Do not let Spring log a rejected frame/payload/token.
            return null;
        }
    }
    private long board(Pattern pattern, String destination) {
        var matcher = pattern.matcher(destination == null ? "" : destination);
        if (!matcher.matches()) throw new AccessDeniedException("PROTOCOL_REJECTED");
        try { return Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException ex) { throw new AccessDeniedException("PROTOCOL_REJECTED"); }
    }
}
