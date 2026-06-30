package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Evento de dibujo difundido a los suscriptores del canal {@code /topic/whiteboards/{id}}.
 *
 * <p>Se construye en el servidor a partir del evento validado y del actor autenticado.
 * Solo expone un nombre seguro del actor y su rol; nunca correo, token ni otros datos
 * sensibles.</p>
 */
public record WhiteboardDrawEventResponse(
        Long sessionId,
        WhiteboardDrawEventType eventType,
        WhiteboardDrawTool tool,
        String color,
        Double strokeWidth,
        Double eraserSize,
        List<WhiteboardPoint> points,
        Role actorRole,
        String actorDisplayName,
        String clientEventId,
        LocalDateTime occurredAt,
        String textId,
        Double fontSize,
        List<WhiteboardTextRun> runs
) {
}
