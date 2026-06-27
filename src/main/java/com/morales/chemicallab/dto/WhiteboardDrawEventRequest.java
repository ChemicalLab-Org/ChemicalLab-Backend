package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Evento de dibujo entrante por WebSocket (canal {@code /app/whiteboards/{sessionId}/draw}).
 *
 * <p>El backend nunca confía en el cliente: valida el estado de la sesión y el permiso
 * efectivo del actor antes de difundir. El actor se resuelve del principal autenticado del
 * STOMP, no de este cuerpo. La cantidad de puntos se acota para evitar payloads enormes.</p>
 *
 * @param clientEventId identificador del evento generado por el cliente, útil para
 *                      deduplicar/reconciliar en el frontend (opcional).
 */
public record WhiteboardDrawEventRequest(
        WhiteboardDrawEventType eventType,
        WhiteboardDrawTool tool,
        String color,
        Double strokeWidth,
        Double eraserSize,
        List<WhiteboardPoint> points,
        String clientEventId
) {
}
