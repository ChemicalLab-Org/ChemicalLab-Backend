package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resumen de trazabilidad de un intento, para que el docente revise incidencias básicas
 * ocurridas durante la evaluación: cuándo inició y finalizó, cuánto tiempo usó, su estado
 * final, cuántas salidas/regresos de pestaña hubo, cuántos intentos de salida y qué
 * herramientas permitidas consultó, además de una línea de tiempo simple de eventos.
 *
 * <p>Nunca incluye respuestas, claves, contenido de preguntas ni datos sensibles: es
 * trazabilidad del comportamiento del intento, separada de los logs generales de
 * auditoría y de la corrección de respuestas.</p>
 */
public record AttemptTraceabilityResponse(
        Long attemptId,
        Long evaluationId,
        String evaluationTitle,
        Long studentId,
        String studentCode,
        String studentName,
        // Estado final del intento (IN_PROGRESS si aún no terminó).
        AttemptStatus finalStatus,
        LocalDateTime startedAt,
        LocalDateTime submittedAt,
        // Tiempo usado en segundos, calculado en el backend con los timestamps del intento.
        Long timeUsedSeconds,
        // Si la evaluación tiene activada la detección de salida de pestaña.
        boolean trackTabExit,
        long totalEvents,
        // Salidas de pestaña/ventana (TAB_HIDDEN + WINDOW_BLUR).
        long tabExitCount,
        // Regresos a la pestaña/ventana (TAB_VISIBLE + WINDOW_FOCUS).
        long tabReturnCount,
        // Veces que el estudiante intentó salir del intento (EXIT_ATTEMPTED).
        long exitAttemptCount,
        // Herramientas permitidas consultadas durante el intento (p. ej. PERIODIC_TABLE).
        List<String> toolsUsed,
        // Línea de tiempo cronológica de eventos del intento.
        List<AttemptEventResponse> events
) {}
