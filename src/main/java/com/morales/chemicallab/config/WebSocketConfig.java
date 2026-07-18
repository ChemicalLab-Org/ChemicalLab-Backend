package com.morales.chemicallab.config;

import com.morales.chemicallab.security.WhiteboardStompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * Configuración WebSocket/STOMP para la sincronización en vivo de la pizarra interactiva.
 *
 * <ul>
 *   <li>Endpoint de conexión: {@code /ws} (con SockJS como respaldo para navegadores).</li>
 *   <li>Broker simple para difundir en {@code /topic} y colas privadas en {@code /user}.</li>
 *   <li>Prefijo de aplicación {@code /app} para los mensajes entrantes
 *       ({@code /app/whiteboards/{id}/draw} y {@code /presence}).</li>
 *   <li>Autenticación en el CONNECT vía {@link WhiteboardStompAuthChannelInterceptor},
 *       reutilizando el JWT del proyecto.</li>
 * </ul>
 *
 * <p>Los orígenes permitidos reutilizan la propiedad {@code app.cors.allowed-origins} ya usada
 * por la configuración CORS del proyecto.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WhiteboardStompAuthChannelInterceptor authChannelInterceptor;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = parseOrigins();
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Autentica cada conexión STOMP (CONNECT) con el JWT antes de procesar mensajes.
        registration.interceptors(authChannelInterceptor);
    }

    private String[] parseOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }
}
