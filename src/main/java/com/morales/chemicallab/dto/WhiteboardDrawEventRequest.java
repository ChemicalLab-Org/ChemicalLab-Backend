package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Evento de dibujo entrante por WebSocket (canal {@code /app/whiteboards/{sessionId}/draw}).
 *
 * <p>El backend nunca confía en el cliente: valida el estado de la sesión y el permiso
 * efectivo del actor antes de difundir. El actor se resuelve del principal autenticado del
 * STOMP, no de este cuerpo. La cantidad de puntos se acota para evitar payloads enormes.</p>
 *
 * <p>Los campos {@code textId}, {@code fontSize} y {@code runs} solo aplican a los eventos de
 * texto ({@code TEXT}/{@code TEXT_DELETE}); para {@code DRAW}/{@code ERASE}/{@code CLEAR} se
 * ignoran. En un evento de texto, la posición del bloque viaja como un único punto en
 * {@code points}.</p>
 *
 * @param clientEventId identificador del evento generado por el cliente, útil para
 *                      deduplicar/reconciliar en el frontend (opcional).
 * @param strokeId      identificador estable del trazo en DRAW/ERASE/STROKE_DELETE. Permite a
 *                      todos los clientes referirse al mismo trazo (deshacer/rehacer). Opcional
 *                      en DRAW/ERASE; obligatorio en STROKE_DELETE.
 * @param strokeIndex   posición del trazo dentro del lienzo al restaurarlo (rehacer). Opcional;
 *                      si falta, el trazo se añade al final.
 */
public record WhiteboardDrawEventRequest(
        WhiteboardDrawEventType eventType,
        WhiteboardDrawTool tool,
        String color,
        Double strokeWidth,
        Double eraserSize,
        List<WhiteboardPoint> points,
        String clientEventId,
        String textId,
        Double fontSize,
        List<WhiteboardTextRun> runs,
        String shapeId,
        String strokeId,
        Integer strokeIndex
) {
    public WhiteboardDrawEventRequest(WhiteboardDrawEventType eventType,
                                      WhiteboardDrawTool tool,
                                      String color,
                                      Double strokeWidth,
                                      Double eraserSize,
                                      List<WhiteboardPoint> points,
                                      String clientEventId,
                                      String textId,
                                      Double fontSize,
                                      List<WhiteboardTextRun> runs,
                                      String shapeId) {
        this(eventType, tool, color, strokeWidth, eraserSize, points, clientEventId,
                textId, fontSize, runs, shapeId, null, null);
    }

    public WhiteboardDrawEventRequest(WhiteboardDrawEventType eventType,
                                      WhiteboardDrawTool tool,
                                      String color,
                                      Double strokeWidth,
                                      Double eraserSize,
                                      List<WhiteboardPoint> points,
                                      String clientEventId,
                                      String textId,
                                      Double fontSize,
                                      List<WhiteboardTextRun> runs) {
        this(eventType, tool, color, strokeWidth, eraserSize, points, clientEventId,
                textId, fontSize, runs, null, null, null);
    }
}
