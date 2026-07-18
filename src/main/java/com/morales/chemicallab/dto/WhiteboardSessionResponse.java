package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.WhiteboardSessionStatus;

import java.time.LocalDateTime;

/**
 * Vista de una sesión para el docente propietario (listado y resultado de acciones). No
 * incluye los bytes de la captura final; solo si está disponible ({@link #snapshotAvailable}).
 */
public record WhiteboardSessionResponse(
        Long id,
        String name,
        String description,
        Long teacherId,
        String teacherName,
        String grade,
        String section,
        WhiteboardSessionStatus status,
        boolean interactionEnabled,
        int participantCount,
        boolean snapshotAvailable,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime pausedAt,
        LocalDateTime resumedAt,
        LocalDateTime closedAt,
        String closedBy
) {
}
