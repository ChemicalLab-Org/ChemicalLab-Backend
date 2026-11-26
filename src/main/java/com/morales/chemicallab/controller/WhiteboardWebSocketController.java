package com.morales.chemicallab.controller;

import com.morales.chemicallab.security.limits.InputRejectedException;

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
import org.springframework.messaging.handler.annotation.Header;
import com.morales.chemicallab.security.WhiteboardConnections;
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


    private final WhiteboardDrawEventService whiteboardDrawEventService;
    private final WhiteboardSessionService whiteboardSessionService;
    private final WhiteboardConnections connections;
    private final com.morales.chemicallab.security.audit.AuditRejections audit;
    private final com.morales.chemicallab.service.InstitutionalWhiteboardService institutional;

    @MessageMapping("/institutions/{institution}/classes/{clazz}/whiteboards/v1/{board}/draw")
    public void institutionalDraw(@DestinationVariable long institution, @DestinationVariable long clazz,
            @DestinationVariable long board, @Payload WhiteboardDrawEventRequest request,
            @Header("simpSessionId") String connectionId) {
        var target = scopedTarget(connectionId, institution, clazz, board);
        String event = request.clientEventId();
        if (event == null || event.length() != 36) throw InputRejectedException.invalid();
        java.util.UUID id = java.util.UUID.fromString(event);
        if (!id.toString().equals(event)) throw InputRejectedException.invalid();
        institutional.draw(connections.validate(connectionId), target, id, request);
    }

    @MessageMapping("/institutions/{institution}/classes/{clazz}/whiteboards/v1/{board}/ready")
    @SendToUser(destinations = "/queue/whiteboard-errors", broadcast = false)
    public Map<String, Boolean> institutionalReady(@DestinationVariable long institution, @DestinationVariable long clazz,
            @DestinationVariable long board, @Header("simpSessionId") String connectionId) {
        scopedTarget(connectionId, institution, clazz, board);
        connections.ready(connectionId);
        return Map.of("ready", true);
    }

    private com.morales.chemicallab.security.WhiteboardScope.Target scopedTarget(String id, long institution, long clazz, long board) {
        var target = connections.target(id);
        if (target == null || target.scope().institution() != institution || target.scope().clazz() != clazz
                || target.board() != board) {
            throw new org.springframework.security.access.AccessDeniedException("BOARD_ACCESS_LOST");
        }
        return target;
    }

    @MessageMapping("/whiteboards/{sessionId}/draw")
    public void draw(@DestinationVariable Long sessionId,
                     @Payload WhiteboardDrawEventRequest request,
                     @Header("simpSessionId") String connectionId,
                     Principal principal) {
        requirePrincipal(principal);
        connections.observe(connectionId, sessionId);
        whiteboardDrawEventService.processDrawEvent(principal.getName(), sessionId, request);
    }

    @MessageMapping("/whiteboards/{sessionId}/presence")
    public void presence(@DestinationVariable Long sessionId, @Header("simpSessionId") String connectionId, Principal principal) {
        requirePrincipal(principal);
        connections.observe(connectionId, sessionId);
        whiteboardSessionService.registerPresence(principal.getName(), sessionId);
    }

    /**
     * Devuelve al cliente que originó el mensaje los errores de validación o permiso, en su
     * cola privada {@code /user/queue/whiteboard-errors}, sin afectar a los demás suscriptores.
     */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/whiteboard-errors", broadcast = false)
    public Map<String, Object> handleError(Exception ex, org.springframework.messaging.Message<?> message) {
        var accessor=org.springframework.messaging.simp.stomp.StompHeaderAccessor.wrap(message);
        var actor=accessor.getUser() instanceof org.springframework.security.core.Authentication authentication && authentication.getPrincipal() instanceof com.morales.chemicallab.security.SessionPrincipal p ? p : null;
        var correlation=message.getHeaders().get("auditCorrelation") instanceof java.util.UUID id ? id : java.util.UUID.randomUUID();
        boolean quota=com.morales.chemicallab.security.storage.StorageErrors.quota(ex);
        boolean rejected=quota || ex instanceof IllegalArgumentException || ex instanceof org.springframework.security.access.AccessDeniedException
            || ex instanceof org.springframework.web.server.ResponseStatusException status && status.getStatusCode().is4xxClientError();
        try(var context=com.morales.chemicallab.security.audit.AuditContext.open(correlation)){
            if(audit!=null) audit.record(rejected ? com.morales.chemicallab.entity.LogEventType.STOMP_DENIED : com.morales.chemicallab.entity.LogEventType.OPERATION_FAILED,
                actor,"STOMP",com.morales.chemicallab.security.audit.AuditSafety.requestedId(accessor.getDestination()),"reason="+(quota?"STORAGE_QUOTA_EXCEEDED":rejected?"STOMP_PERMISSION":"STOMP_FAILURE"),null);
        }
        String error = quota ? com.morales.chemicallab.security.storage.StorageErrors.message() : ex instanceof IllegalArgumentException ? ex.getMessage() : "No se pudo aplicar la acción. Resincroniza la pizarra.";
        String id = org.springframework.messaging.simp.stomp.StompHeaderAccessor.wrap(message).getFirstNativeHeader("client-event-id");
        if (id == null || id.length() > 100) id = "";
        String code = quota ? "STORAGE_QUOTA_EXCEEDED" : ex instanceof InputRejectedException rejection ? rejection.code() : "DRAW_REJECTED";
        return Map.of("code", code, "resync", true, "accepted", false, "clientEventId", id, "error", error, "auditCorrelationId",correlation.toString());
    }

    private void requirePrincipal(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new IllegalArgumentException("No autenticado: no se permite dibujar de forma anónima.");
        }
    }
}
