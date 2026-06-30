package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.WhiteboardControlEventResponse;
import com.morales.chemicallab.dto.WhiteboardDrawEventResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Difunde eventos de la pizarra a los suscriptores del canal de una sesión
 * ({@code /topic/whiteboards/{sessionId}}). Encapsula el {@link SimpMessagingTemplate} para
 * que el resto de servicios no dependan directamente del transporte.
 *
 * <p>La difusión nunca propaga errores: un fallo al enviar por WebSocket (p. ej. sin
 * suscriptores o broker no disponible) no debe romper la operación REST que la originó.</p>
 */
@Service
@RequiredArgsConstructor
public class WhiteboardBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardBroadcastService.class);

    private static final String TOPIC_PREFIX = "/topic/whiteboards/";

    private final SimpMessagingTemplate messagingTemplate;

    /** Difunde un evento de dibujo ya validado a los suscriptores de la sesión. */
    public void broadcastDraw(WhiteboardDrawEventResponse event) {
        send(event.sessionId(), event);
    }

    /** Difunde un evento de control (pausa, reanudación, cierre, permisos) de la sesión. */
    public void broadcastControl(WhiteboardControlEventResponse event) {
        send(event.sessionId(), event);
    }

    private void send(Long sessionId, Object payload) {
        if (sessionId == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSend(TOPIC_PREFIX + sessionId, payload);
        } catch (Exception ex) {
            log.warn("No fue posible difundir el evento de la pizarra {}: {}", sessionId, ex.getMessage());
        }
    }
}
