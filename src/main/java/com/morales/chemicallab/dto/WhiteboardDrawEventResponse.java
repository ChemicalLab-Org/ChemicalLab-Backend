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
 *
 * @param strokeId    identificador estable del trazo (DRAW/ERASE/STROKE_DELETE), si el cliente
 *                    lo envió. Permite deshacer/rehacer por identidad en todos los clientes.
 * @param strokeIndex posición del trazo al restaurarlo (rehacer), si el cliente la envió.
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
        List<WhiteboardTextRun> runs,
        String shapeId,
        String strokeId,
        Integer strokeIndex
) {
    public WhiteboardDrawEventResponse(Long sessionId,
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
                                       List<WhiteboardTextRun> runs,
                                       String shapeId) {
        this(sessionId, eventType, tool, color, strokeWidth, eraserSize, points, actorRole,
                actorDisplayName, clientEventId, occurredAt, textId, fontSize, runs, shapeId,
                null, null);
    }

    public WhiteboardDrawEventResponse(Long sessionId,
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
                                       List<WhiteboardTextRun> runs) {
        this(sessionId, eventType, tool, color, strokeWidth, eraserSize, points, actorRole,
                actorDisplayName, clientEventId, occurredAt, textId, fontSize, runs, null,
                null, null);
    }
}
