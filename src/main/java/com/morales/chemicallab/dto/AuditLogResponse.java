package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.Role;

import java.time.LocalDateTime;

/**
 * Representación de un evento de trazabilidad para el panel administrativo. No expone
 * datos sensibles: el contenido proviene directamente del registro almacenado, que por
 * diseño nunca guarda contraseñas ni tokens.
 */
public record AuditLogResponse(
        Long id,
        LogEventType eventType,
        LogCategory category,
        LogSeverity severity,
        Long actorUserId,
        String actorUsername,
        Role actorRole,
        String targetType,
        Long targetId,
        String targetLabel,
        String action,
        String description,
        String metadata,
        LocalDateTime createdAt
) {}
