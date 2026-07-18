package com.morales.chemicallab.security;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Autentica las conexiones STOMP de la pizarra reutilizando el JWT del proyecto.
 *
 * <p>En el frame {@code CONNECT} se lee la cabecera {@code Authorization: Bearer <token>},
 * se valida con {@link JwtService} y se carga el usuario con
 * {@link CustomUserDetailsService}. El usuario autenticado se asocia a la sesión STOMP, de
 * modo que los controladores reciben un {@link java.security.Principal} de confianza y no se
 * confía en identificadores enviados por el cliente.</p>
 *
 * <p>No se acepta una conexión sin token válido: el canal no queda abierto de forma anónima
 * para dibujar.</p>
 */
@Component
@RequiredArgsConstructor
public class WhiteboardStompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new IllegalArgumentException("Falta el token de autenticación en la conexión.");
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        UsernamePasswordAuthenticationToken authentication = authenticate(token);
        if (authentication == null) {
            throw new IllegalArgumentException("Token de autenticación inválido o expirado.");
        }

        accessor.setUser(authentication);
        return message;
    }

    private UsernamePasswordAuthenticationToken authenticate(String token) {
        try {
            String username = jwtService.extractUsername(token);
            if (username == null) {
                return null;
            }
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (!jwtService.isTokenValid(token, userDetails)) {
                return null;
            }
            return new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
        } catch (Exception ex) {
            return null;
        }
    }
}
