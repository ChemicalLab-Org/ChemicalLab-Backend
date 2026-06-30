package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.WhiteboardSessionStatus;

import java.time.LocalDateTime;

/**
 * Evento de control difundido al canal de la sesión cuando el docente pausa, reanuda, cierra
 * o cambia permisos. Lleva la información mínima para que el frontend reaccione en vivo.
 *
 * @param targetStudentId alumno afectado cuando el evento es de permiso individual; null en
 *                        el resto de casos.
 */
public record WhiteboardControlEventResponse(
        Long sessionId,
        WhiteboardControlEventType eventType,
        WhiteboardSessionStatus status,
        Boolean interactionEnabled,
        Long targetStudentId,
        LocalDateTime occurredAt
) {
}
