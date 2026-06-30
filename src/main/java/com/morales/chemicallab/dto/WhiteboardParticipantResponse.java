package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.WhiteboardInteractionOverride;

import java.time.LocalDateTime;

/**
 * Vista segura de un participante para el docente: identifica al alumno y su permiso
 * individual y efectivo, sin exponer datos sensibles (correo, contraseña, token).
 */
public record WhiteboardParticipantResponse(
        Long id,
        Long studentId,
        String studentName,
        String studentCode,
        String grade,
        String section,
        WhiteboardInteractionOverride interactionOverride,
        boolean effectiveInteraction,
        LocalDateTime joinedAt,
        LocalDateTime lastSeenAt
) {
}
