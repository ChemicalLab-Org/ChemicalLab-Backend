package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.UsageModule;

import java.time.LocalDateTime;

/**
 * Vista segura de un evento de uso para el panel administrativo. No expone datos
 * sensibles: el contenido proviene de registros que por diseño nunca los almacenan.
 */
public record UsageEventResponse(
        Long id,
        Long userId,
        String username,
        Role userRole,
        UsageModule module,
        UsageEventType eventType,
        String resourceType,
        String resourceId,
        String description,
        String metadata,
        LocalDateTime occurredAt
) {}
