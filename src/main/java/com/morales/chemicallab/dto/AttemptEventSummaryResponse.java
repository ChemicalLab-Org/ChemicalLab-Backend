package com.morales.chemicallab.dto;

/**
 * Resumen devuelto al estudiante tras registrar una incidencia de foco. No expone datos
 * de otros intentos ni información sensible: solo si se registró el evento y los
 * contadores acumulados del propio intento.
 */
public record AttemptEventSummaryResponse(
        Long attemptId,
        // true si el evento se registró; false si se descartó por throttling/duplicado.
        boolean recorded,
        // Total de incidencias acumuladas en el intento.
        long totalEvents,
        // Cantidad de "salidas" (pestaña oculta o ventana sin foco) en el intento.
        long tabExitCount
) {}
