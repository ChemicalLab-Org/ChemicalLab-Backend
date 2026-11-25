package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.WhiteboardControlEventResponse;
import com.morales.chemicallab.dto.WhiteboardDrawEventResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        if (sessionId != null) send(TOPIC_PREFIX + sessionId, payload);
    }

    void send(String destination, Object payload) {
        send(destination, payload, null);
    }

    /** Zero restricts the event to teachers. A positive ID also allows the affected student. */
    void send(String destination, Object payload, Long participantAudience) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { sendCommitted(destination, payload, participantAudience); }
            });
        } else sendCommitted(destination, payload, participantAudience);
    }

    private void sendCommitted(String destination, Object payload, Long participantAudience) {
        try {
            if (participantAudience == null) {
                messagingTemplate.convertAndSend(destination, payload);
            } else {
                var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                headers.setHeader("boardParticipantAudience", participantAudience);
                headers.setLeaveMutable(true);
                messagingTemplate.convertAndSend(destination, payload, headers.getMessageHeaders());
            }
        } catch (Exception ex) {
            log.warn("No fue posible difundir el evento de la pizarra {}", destination);
        }
    }
}
