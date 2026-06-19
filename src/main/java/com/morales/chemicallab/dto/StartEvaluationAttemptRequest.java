package com.morales.chemicallab.dto;

/**
 * Cuerpo opcional al iniciar un intento. La evaluación se toma de la ruta y la
 * asignación se resuelve en el backend a partir del grado/sección del estudiante,
 * por lo que {@code assignmentId} es solo informativo y puede omitirse.
 */
public record StartEvaluationAttemptRequest(
        Long assignmentId
) {}
