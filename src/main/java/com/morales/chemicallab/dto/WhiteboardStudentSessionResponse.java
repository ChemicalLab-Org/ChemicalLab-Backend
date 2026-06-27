package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.WhiteboardSessionStatus;

import java.time.LocalDateTime;

/**
 * Vista segura de una sesión para el estudiante (sesiones disponibles y detalle para unirse).
 * Expone solo información necesaria: no incluye datos internos del docente ni de otros
 * alumnos. {@link #joined} indica si el estudiante ya se unió y {@link #canInteract} su
 * permiso efectivo de dibujo en la sesión.
 */
public record WhiteboardStudentSessionResponse(
        Long id,
        String name,
        String description,
        String teacherName,
        String grade,
        String section,
        WhiteboardSessionStatus status,
        boolean interactionEnabled,
        boolean joined,
        boolean canInteract,
        boolean snapshotAvailable,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime closedAt
) {
}
