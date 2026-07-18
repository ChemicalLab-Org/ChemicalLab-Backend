package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptEventType;

import java.time.LocalDateTime;

/**
 * Evento individual de la línea de tiempo de un intento, para la vista de trazabilidad
 * del docente. No expone respuestas, claves ni datos sensibles: solo el tipo de evento,
 * el momento, una descripción breve y, si existe, una metadata segura y acotada.
 */
public record AttemptEventResponse(
        Long id,
        AttemptEventType eventType,
        String description,
        // Metadata segura del evento (p. ej. "tool=PERIODIC_TABLE"); puede ser null.
        String metadata,
        LocalDateTime occurredAt
) {}
