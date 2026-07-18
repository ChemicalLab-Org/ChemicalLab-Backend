package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.WhiteboardSessionStatus;

import java.time.LocalDateTime;

/**
 * Entrada del historial de sesiones cerradas visible para el estudiante: metadata segura y
 * disponibilidad de la captura final.
 */
public record WhiteboardHistoryItemResponse(
        Long id,
        String name,
        String teacherName,
        String grade,
        String section,
        WhiteboardSessionStatus status,
        boolean snapshotAvailable,
        LocalDateTime closedAt
) {
}
