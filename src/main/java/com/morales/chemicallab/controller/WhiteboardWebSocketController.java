package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.WhiteboardDrawEventRequest;
import com.morales.chemicallab.service.WhiteboardDrawEventService;
import com.morales.chemicallab.service.WhiteboardSessionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/**
 * Controlador WebSocket/STOMP de la pizarra en vivo.
 *
 * <ul>
 *   <li>{@code /app/whiteboards/{sessionId}/draw}: evento de dibujo. El backend valida estado
 *       y permiso antes de difundir a {@code /topic/whiteboards/{sessionId}}.</li>
 *   <li>{@code /app/whiteboards/{sessionId}/presence}: latido de presencia del estudiante.</li>
 * </ul>
 *
 * <p>El actor se obtiene del {@link Principal} autenticado del canal (resuelto del token JWT
 * en el CONNECT), nunca de datos enviados por el cliente. No se permite dibujar de forma
 * anónima: si no hay principal, el evento se rechaza.</p>
 */
@Controller
@RequiredArgsConstructor
public class WhiteboardWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardWebSocketController.class);

    private final WhiteboardDrawEventService whiteboardDrawEventService;
    private final WhiteboardSessionService whiteboardSessionService;

    @MessageMapping("/whiteboards/{sessionId}/draw")
    public void draw(@DestinationVariable Long sessionId,
                     @Payload WhiteboardDrawEventRequest request,
                     Principal principal) {
        requirePrincipal(principal);
        whiteboardDrawEventService.processDrawEvent(principal.getName(), sessionId, request);
    }

    @MessageMapping("/whiteboards/{sessionId}/presence")
    public void presence(@DestinationVariable Long sessionId, Principal principal) {
        requirePrincipal(principal);
        whiteboardSessionService.registerPresence(principal.getName(), sessionId);
    }

    /**
     * Devuelve al cliente que originó el mensaje los errores de validación o permiso, en su
     * cola privada {@code /user/queue/whiteboard-errors}, sin afectar a los demás suscriptores.
     */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/whiteboard-errors", broadcast = false)
    public Map<String, String> handleError(Exception ex) {
        log.debug("Evento de pizarra rechazado: {}", ex.getMessage());
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Evento de pizarra inválido.");
    }

    private void requirePrincipal(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new IllegalArgumentException("No autenticado: no se permite dibujar de forma anónima.");
        }
    }
}
