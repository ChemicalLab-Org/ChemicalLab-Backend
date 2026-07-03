package com.morales.chemicallab.dto;

/**
 * Indicadores agregados para el panel administrativo de métricas de uso.
 *
 * <p>Todos los valores son conteos <strong>históricos</strong> calculados sobre datos reales
 * ya persistidos (eventos de uso, logs de trazabilidad, sesiones de pizarra e intentos de
 * evaluación). No se estiman ni se proyectan valores: si un indicador del Project Charter no
 * puede calcularse con estas fuentes, el frontend lo presenta como pendiente o no disponible
 * en lugar de inventarlo.</p>
 *
 * <p>Ninguno de los campos contiene datos sensibles: son únicamente conteos.</p>
 */
public record UsagePanelResponse(
        GeneralStats general,
        WhiteboardStats whiteboard,
        EvaluationStats evaluations,
        TraceabilityStats traceability
) {

    /** Actividad global registrada por las métricas de uso. */
    public record GeneralStats(
            long totalEvents,
            long activeUsers,
            long modulesUsed,
            long moduleAccessEvents,
            long compoundFormationEvents
    ) {}

    /** Uso de la pizarra interactiva (sesiones, participación y trazabilidad propia). */
    public record WhiteboardStats(
            long sessionsCreated,
            long sessionsActive,
            long sessionsClosed,
            long sessionsWithSnapshot,
            long studentJoinEvents,
            long distinctStudents,
            long auditEvents
    ) {}

    /** Uso del módulo de evaluaciones y resultados. */
    public record EvaluationStats(
            long published,
            long openedEvents,
            long startedEvents,
            long attemptsTotal,
            long attemptsSubmitted,
            long attemptsGraded,
            long resultViewEvents
    ) {}

    /** Trazabilidad y seguridad: logs de auditoría y accesos. */
    public record TraceabilityStats(
            long auditTotal,
            long auditWarnings,
            long auditErrors,
            long loginSuccess,
            long loginFailed
    ) {}
}
