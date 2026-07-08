package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.UsageModule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle del registro de uso de un usuario concreto: sus indicadores consolidados más
 * los eventos de uso recientes, las evaluaciones asociadas y las incidencias técnicas
 * atribuibles. Nunca incluye contraseñas, tokens, respuestas de estudiantes ni payloads:
 * los eventos e incidencias provienen de tablas que por diseño no almacenan datos
 * sensibles, y de las evaluaciones solo se exponen título y conteos.
 */
public record StudentUsageRecordDetailResponse(
        StudentUsageRecordResponse summary,
        List<UsageEventItem> recentEvents,
        List<EvaluationUsageItem> evaluations,
        List<IncidentItem> incidents) {

    /** Evento de uso reciente del usuario (sin metadata ni payloads). */
    public record UsageEventItem(
            UsageModule module,
            UsageEventType eventType,
            String resourceType,
            String description,
            LocalDateTime occurredAt) {
    }

    /** Relación del usuario con una evaluación: asignación e intentos, sin respuestas. */
    public record EvaluationUsageItem(
            Long evaluationId,
            String title,
            boolean assigned,
            long attemptsCount,
            boolean completed,
            LocalDateTime lastAttemptAt) {
    }

    /** Incidencia técnica registrada en los logs de trazabilidad, atribuible al usuario. */
    public record IncidentItem(
            LogSeverity severity,
            LogEventType eventType,
            String action,
            String description,
            LocalDateTime createdAt) {
    }
}
