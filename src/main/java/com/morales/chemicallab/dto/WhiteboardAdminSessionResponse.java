package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.WhiteboardSessionStatus;

import java.time.LocalDateTime;

/**
 * Vista supervisada de una sesión para el administrador: solo metadata institucional. El
 * administrador no dibuja ni edita en el MVP; por eso no se incluye la captura completa.
 */
public record WhiteboardAdminSessionResponse(
        Long id,
        String name,
        Long teacherId,
        String teacherName,
        String grade,
        String section,
        WhiteboardSessionStatus status,
        boolean interactionEnabled,
        int participantCount,
        boolean snapshotAvailable,
        LocalDateTime createdAt,
        LocalDateTime closedAt
) {
}
